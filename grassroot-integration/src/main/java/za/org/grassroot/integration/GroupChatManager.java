package za.org.grassroot.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jivesoftware.smack.packet.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.org.grassroot.core.domain.*;
import za.org.grassroot.core.enums.TaskType;
import za.org.grassroot.core.repository.GroupChatMessageStatsRepository;
import za.org.grassroot.core.repository.GroupChatSettingsRepository;
import za.org.grassroot.core.repository.GroupRepository;
import za.org.grassroot.core.repository.UserRepository;
import za.org.grassroot.core.util.DateTimeUtil;
import za.org.grassroot.core.util.UIDGenerator;
import za.org.grassroot.integration.domain.MQTTPayload;
import za.org.grassroot.integration.exception.GroupChatSettingNotFoundException;
import za.org.grassroot.integration.exception.SeloParseDateTimeFailure;
import za.org.grassroot.integration.mqtt.MqttObjectMapper;
import za.org.grassroot.integration.utils.MessageUtils;
import za.org.grassroot.integration.xmpp.GcmService;
import za.org.grassroot.integration.xmpp.GcmXmppMessageCodec;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Created by paballo on 2016/09/08.
 */
@Service
public class GroupChatManager implements GroupChatService {

    private static final Logger logger = LoggerFactory.getLogger(GroupChatManager.class);

    private static final DateTimeFormatter cmdMessageFormat = DateTimeFormatter.ofPattern("HH:mm, EEE d MMM");
    private static final String GRASSROOT_SYSTEM = "Grassroot";
    private static final String ERROR = "error";
    private static final String SYNC = "sync";

    private static final String SERVER_PROMPT = "SERVER_PROMPT"; // for command prompts (e.g., call a vote?)
    private static final String SERVER_MSG = "SERVER_MESSAGE"; // for broadcast messages

    @Value("${gcm.topics.path}")
    private String TOPICS;

    @Value("${mqtt.status.read.threshold:0.5}")
    private Double readStatusThreshold;

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final GroupChatSettingsRepository groupChatSettingsRepository;
    private final LearningService learningService;
    private final MessageChannel gcmXmppOutboundChannel;
    private final MessageChannel mqttOutboundChannel;
    private final GroupChatMessageStatsRepository groupChatMessageStatsRepository;
    private final GcmService gcmService;
    private final MqttObjectMapper payloadMapper;
    private final MessageSourceAccessor messageSourceAccessor;

    @Autowired
    public GroupChatManager(UserRepository userRepository, GroupRepository groupRepository, GroupChatSettingsRepository groupChatSettingsRepository,
                            LearningService learningService, MessageChannel gcmXmppOutboundChannel, MessageChannel mqttOutboundChannel,
                            GroupChatMessageStatsRepository groupChatMessageStatsRepository, GcmService gcmService, MqttObjectMapper payloadMapper,
                            @Qualifier("integrationMessageSourceAccessor") MessageSourceAccessor messageSourceAccessor) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.groupChatSettingsRepository = groupChatSettingsRepository;
        this.learningService = learningService;
        this.gcmXmppOutboundChannel = gcmXmppOutboundChannel;
        this.mqttOutboundChannel = mqttOutboundChannel;
        this.groupChatMessageStatsRepository = groupChatMessageStatsRepository;
        this.gcmService = gcmService;
        this.payloadMapper = payloadMapper;
        this.messageSourceAccessor = messageSourceAccessor;
    }

    @Override
    @Transactional
    public void createUserGroupMessagingSetting(String userUid, String groupUid, boolean active, boolean canSend, boolean canReceive) {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(groupUid);

        User user = userRepository.findOneByUid(userUid);
        Group group = groupRepository.findOneByUid(groupUid);

        GroupChatSettings groupChatSettings = new GroupChatSettings(user, group, active, true, true, true);
        groupChatSettingsRepository.save(groupChatSettings);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "groupChatSettings", key = "userUid + '_'+ groupUid")
    public GroupChatSettings load(String userUid, String groupUid) throws GroupChatSettingNotFoundException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(groupUid);

        User user = userRepository.findOneByUid(userUid);
        Group group = groupRepository.findOneByUid(groupUid);

        GroupChatSettings groupChatSettings = groupChatSettingsRepository.findByUserAndGroup(user, group);

        if (groupChatSettings == null) {
            throw new GroupChatSettingNotFoundException("Group chat setting not found found for user with uid " + userUid);
        }

        return groupChatSettings;
    }


    @Override
    @Async
    public void processCommandMessage(MQTTPayload incoming) {
        logger.info("incoming command message ...");
        String groupUid = incoming.getGroupUid();
        Group group = groupRepository.findOneByUid(groupUid);
        MQTTPayload payload = generateCommandResponseMessage(incoming, group);

        try {
            logger.info("inside payload, time is : {}", payload.getTime().toString());
            final String message = payloadMapper.writeValueAsString(payload);
            logger.info("Outgoing mqtt message to {}, with payload ={}", incoming.getPhoneNumber(), message);
            mqttOutboundChannel.send(MessageBuilder.withPayload(message).
                    setHeader(MqttHeaders.TOPIC, incoming.getPhoneNumber()).build());
        } catch (JsonProcessingException e) {
            // todo : send back a "sorry we couldn't handle it" message
            logger.debug("Message conversion failed with error ={}", e.getMessage());
        }

    }


    @Override
    @Async
    @Transactional
    public void markMessagesAsRead(String groupUid, String groupName, Set<String> messageUids) {
        for (String messageUid : messageUids) {
            MQTTPayload payload = new MQTTPayload(messageUid, groupUid, groupName, GRASSROOT_SYSTEM, "update_read_status");;
            GroupChatMessageStats groupChatMessageStats = groupChatMessageStatsRepository.findByUidAndRead(messageUid, false);
            if (groupChatMessageStats != null) {
                groupChatMessageStats.incrementReadCount();
                User user = groupChatMessageStats.getUser();
                if (groupChatMessageStats.getTimesRead() / groupChatMessageStats.getIntendedReceipients() > readStatusThreshold) {
                    groupChatMessageStats.setRead(true);
                    try {
                        final ObjectMapper mapper = new ObjectMapper();
                        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
                        final String message = mapper.writeValueAsString(payload);
                        mqttOutboundChannel.send(MessageBuilder.withPayload(message).
                                setHeader(MqttHeaders.TOPIC, user.getPhoneNumber()).build());
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }

                }

            }
        }
    }


    @Override
    @Transactional(readOnly = true)
    public boolean isCanSend(String userUid, String groupUid) throws GroupChatSettingNotFoundException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(groupUid);

        User user = userRepository.findOneByUid(userUid);
        Group group = groupRepository.findOneByUid(groupUid);

        GroupChatSettings groupChatSettings = groupChatSettingsRepository.findByUserAndGroup(user, group);
        if (null == groupChatSettings) {
            throw new GroupChatSettingNotFoundException("Message settings not found for user with uid " + userUid);
        }

        return groupChatSettings.isCanSend();
    }

    @Override
    @Transactional
    public void updateActivityStatus(String userUid, String groupUid, boolean active, boolean userInitiated) throws GroupChatSettingNotFoundException {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(groupUid);

        User user = userRepository.findOneByUid(userUid);
        Group group = groupRepository.findOneByUid(groupUid);

        GroupChatSettings groupChatSettings = groupChatSettingsRepository.findByUserAndGroup(user, group);
        if (null == groupChatSettings) {
            throw new GroupChatSettingNotFoundException("Message settings not found for user with uid " + userUid);
        }
        groupChatSettings.setActive(active);
        groupChatSettings.setUserInitiated(userInitiated);
        groupChatSettings.setCanSend(active);
        if (userInitiated) groupChatSettings.setCanReceive(active);
        if(!userInitiated && !active){
            final MQTTPayload payload = generateUserMutedResponseData(group);
            final String message;
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,true);
                message = mapper.writeValueAsString(payload);
                mqttOutboundChannel.send(MessageBuilder.withPayload(message).
                        setHeader(MqttHeaders.TOPIC, user.getPhoneNumber()).build());
            } catch (JsonProcessingException e) {
                logger.debug("Error parsing message");
            }

        }
        if (userInitiated) {
            groupChatSettings.setCanReceive(active);
        }
        groupChatSettingsRepository.save(groupChatSettings);

    }

    @Override
    @Transactional
    @Async
    public void createGroupChatMessageStats(MQTTPayload payload) {
        Objects.requireNonNull(payload);
        Group group = groupRepository.findOneByUid(payload.getGroupUid());
        User user = userRepository.findByPhoneNumber(payload.getPhoneNumber());
        if(group !=null && user !=null) {
            Long numberOfIntendedRecepients =
                    groupChatSettingsRepository.countByGroupAndActive(group, true);

            GroupChatMessageStats groupChatMessageStats = new GroupChatMessageStats(payload.getUid(), group, user, numberOfIntendedRecepients, 1L, false);
            groupChatMessageStatsRepository.save(groupChatMessageStats);
            pingUsersForGroupChat(group);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean messengerSettingExist(String userUid, String groupUid) {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(groupUid);

        User user = userRepository.findOneByUid(userUid);
        Group group = groupRepository.findOneByUid(groupUid);

        GroupChatSettings groupChatSettings = groupChatSettingsRepository.findByUserAndGroup(user, group);
        return (groupChatSettings != null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupChatSettings> loadUsersToBeUnmuted() {
        return groupChatSettingsRepository.findByActiveAndUserInitiatedAndReactivationTimeBefore(false, false, Instant.now());

    }

    @Override
    @Transactional(readOnly = true)
    public List<String> usersMutedInGroup(String groupUid) {
        Objects.requireNonNull(groupUid);
        Group group = groupRepository.findOneByUid(groupUid);
        List<GroupChatSettings> groupChatSettingses = groupChatSettingsRepository.findByGroupAndActiveAndCanSend(group, true, false);
        List<String> mutedUsersUids = new ArrayList<>();
        for (GroupChatSettings groupChatSettings : groupChatSettingses) {
            User user = groupChatSettings.getUser();
            mutedUsersUids.add(user.getUsername());
        }
        return mutedUsersUids;
    }

    private void pingUsersForGroupChat(Group group) {
        Map<String, Object> data = MessageUtils.generatePingMessageData(group);
        org.springframework.messaging.Message<Message> gcmMessage = GcmXmppMessageCodec.encode(TOPICS.concat(group.getUid()), (String) data.get("messageId"),
                null, data);
        gcmXmppOutboundChannel.send(gcmMessage);
    }

    @Override
    @Async
    public void pingToSync(User initiator, User addedUser, Group group){
        MQTTPayload payload = generateSyncData(initiator, group);
        Map<String, Object> data = MessageUtils.generatePingMessageData(group);
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,true);
            final String message = mapper.writeValueAsString(payload);
            mqttOutboundChannel.send(MessageBuilder.withPayload(message).
                    setHeader(MqttHeaders.TOPIC, addedUser.getPhoneNumber()).build());
            final String gcmKey = gcmService.getGcmKey(addedUser);
            if (gcmKey !=null) {
                org.springframework.messaging.Message<Message> gcmMessage = GcmXmppMessageCodec.encode(gcmKey,
                        (String) data.get("messageId"), null, data);
                gcmXmppOutboundChannel.send(gcmMessage);

            }
        } catch (JsonProcessingException e) {
            logger.debug("Error sending sync request to user with number ={}", addedUser.getPhoneNumber());
        }
    }

    @Async
    @Override
    @Transactional
    public void addAllGroupMembersToChat(Group group, User initiatingUser) {
        String groupUid = group.getUid();
        Set<Membership> memberships = group.getMemberships();
        logger.info("handling group chat topic subscription ...");
        for (Membership membership : memberships) {
            User user = membership.getUser();
            if (gcmService.hasGcmKey(user) && !messengerSettingExist(user.getUid(), groupUid)) {
                createUserGroupMessagingSetting(user.getUid(), groupUid, true, true, true);
                String registrationId = gcmService.getGcmKey(user);
                try {
                    gcmService.subscribeToTopic(registrationId, groupUid);
                    pingToSync(initiatingUser,user,group);
                } catch (IOException e) {
                    logger.info("Could not subscribe user to group topic {}", groupUid);
                }

            }
        }
        logger.info("finished with group chat topic subscription ...");
    }

    private MQTTPayload generateInvalidCommandResponseData(MQTTPayload input, Group group) {
        String responseMessage = messageSourceAccessor.getMessage("gcm.xmpp.command.invalid");
        MQTTPayload outboundMessage = new MQTTPayload(input.getUid(),
                input.getGroupUid(),
                group.getGroupName(),
                GRASSROOT_SYSTEM,
                ERROR);
        outboundMessage.setText(responseMessage);
        return outboundMessage;
    }

    private MQTTPayload generateSyncData(User addingUser, Group group) {
        String responseMessage = messageSourceAccessor.getMessage("mqtt.member.added",
                new String[]{group.getGroupName(),addingUser.getDisplayName()});
        MQTTPayload outboundMessage = new MQTTPayload(UIDGenerator.generateId(),
                group.getUid(),
                group.getGroupName(),
                GRASSROOT_SYSTEM,
                SYNC);
        outboundMessage.setText(responseMessage);

        return outboundMessage;
    }


    private MQTTPayload generateDateInPastResponse(MQTTPayload input, Group group) {
        String responseMessage = messageSourceAccessor.getMessage("gcm.xmpp.command.timepast");
        MQTTPayload outboundMessage = new MQTTPayload(input.getUid(),
                input.getGroupUid(),
                group.getGroupName(),
                GRASSROOT_SYSTEM,
                ERROR);
        outboundMessage.setText(responseMessage);
        return outboundMessage;
    }

    private MQTTPayload generateCommandResponseData(MQTTPayload input, Group group, TaskType type, String[] tokens,
                                                    LocalDateTime taskDateTime) {

        MQTTPayload outboundMessage = new MQTTPayload(input.getUid(),
                input.getGroupUid(),
                group.getGroupName(),
                GRASSROOT_SYSTEM,
                LocalDateTime.now(),
                taskDateTime,
                SERVER_PROMPT);

        if (TaskType.MEETING.equals(type)) {
            final String text = messageSourceAccessor.getMessage("gcm.xmpp.command.meeting", tokens);
            outboundMessage.setText(text);
            outboundMessage.setTaskType(TaskType.MEETING.name());
        } else if (TaskType.VOTE.equals(type)) {
            final String text = messageSourceAccessor.getMessage("gcm.xmpp.command.vote", tokens);
            outboundMessage.setText(text);
            outboundMessage.setTaskType(TaskType.VOTE.name());
        } else {
            final String text = messageSourceAccessor.getMessage("gcm.xmpp.command.todo", tokens);
            outboundMessage.setText(text);
            outboundMessage.setTaskType(TaskType.TODO.name());
        }

        outboundMessage.setTokens(Arrays.asList(tokens));
        return outboundMessage;
    }


    private MQTTPayload generateCommandResponseMessage(MQTTPayload input, Group group) {
        MQTTPayload data;
        final String msg = input.getText();
        final String[] tokens = splitCommandMessage(msg);

        final TaskType cmdType = msg.contains("/meeting") ? TaskType.MEETING :
                msg.contains("/vote") ? TaskType.VOTE : TaskType.TODO;

        if (tokens.length < (TaskType.MEETING.equals(cmdType) ? 3 : 2)) {
            data = generateInvalidCommandResponseData(input, group);
        } else {
            try {
                final LocalDateTime parsedDateTime = learningService.parse(tokens[1]);
                if (DateTimeUtil.convertToSystemTime(parsedDateTime, DateTimeUtil.getSAST()).isBefore(Instant.now())) {
                    logger.info("time is in the past");
                    data = generateDateInPastResponse(input, group);
                } else {
                    tokens[1] = parsedDateTime.format(cmdMessageFormat);
                    data = generateCommandResponseData(input, group, cmdType, tokens, parsedDateTime);
                }
            } catch (SeloParseDateTimeFailure e) {
                data = generateInvalidCommandResponseData(input, group);
            }
        }

        return data;
    }

    private MQTTPayload generateUserMutedResponseData(Group group) {
        String groupUid = group.getUid();
        String messageId = UIDGenerator.generateId();
        String responseMessage = messageSourceAccessor.getMessage("gcm.xmpp.chat.muted");
        MQTTPayload payload =  new MQTTPayload(messageId,
                groupUid,
                group.getGroupName(),
                group.getGroupName(),
                "normal");
        payload.setText(responseMessage);

        return payload;
    }

    private String[] splitCommandMessage(String message) {
        if (message.contains("/meeting")) {
            message = message.replace("/meeting", "");
        }
        if (message.contains("/vote")) {
            message = message.replace("/vote", "");
        }
        if (message.contains("/todo")) {
            message = message.replace("/todo", "");
        }
        return message.split(",");
    }

}
