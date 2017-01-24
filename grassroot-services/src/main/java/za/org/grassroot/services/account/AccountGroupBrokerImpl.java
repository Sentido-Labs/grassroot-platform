package za.org.grassroot.services.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import za.org.grassroot.core.domain.*;
import za.org.grassroot.core.domain.notification.FreeFormMessageNotification;
import za.org.grassroot.core.enums.AccountLogType;
import za.org.grassroot.core.enums.GroupLogType;
import za.org.grassroot.core.repository.*;
import za.org.grassroot.services.PermissionBroker;
import za.org.grassroot.services.exception.*;
import za.org.grassroot.services.util.FullTextSearchUtils;
import za.org.grassroot.services.util.LogsAndNotificationsBroker;
import za.org.grassroot.services.util.LogsAndNotificationsBundle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static za.org.grassroot.services.specifications.NotificationSpecifications.*;
import static za.org.grassroot.services.specifications.PaidGroupSpecifications.expiresAfter;
import static za.org.grassroot.services.specifications.PaidGroupSpecifications.isForAccount;
import static za.org.grassroot.services.specifications.TodoSpecifications.createdDateBetween;
import static za.org.grassroot.services.specifications.TodoSpecifications.hasGroupAsAncestor;

/**
 * Created by luke on 2016/10/25.
 */
@Service
public class AccountGroupBrokerImpl implements AccountGroupBroker {

    private static final Logger logger = LoggerFactory.getLogger(AccountGroupBrokerImpl.class);

    @Value("${accounts.todos.monthly.free:4}")
    private int FREE_TODOS_PER_MONTH;

    private UserRepository userRepository;
    private GroupRepository groupRepository;
    private PermissionBroker permissionBroker;
    private TodoRepository todoRepository;
    private AccountRepository accountRepository;
    private PaidGroupRepository paidGroupRepository;
    private LogsAndNotificationsBroker logsAndNotificationsBroker;

    @Autowired
    public AccountGroupBrokerImpl(UserRepository userRepository, GroupRepository groupRepository, TodoRepository todoRepository,
                                  PermissionBroker permissionBroker, AccountRepository accountRepository,
                                  PaidGroupRepository paidGroupRepository, LogsAndNotificationsBroker logsAndNotificationsBroker) {
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.todoRepository = todoRepository;
        this.permissionBroker = permissionBroker;
        this.accountRepository = accountRepository;
        this.paidGroupRepository = paidGroupRepository;
        this.logsAndNotificationsBroker = logsAndNotificationsBroker;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Group> fetchGroupsSponsoredByAccount(String accountUid) {
        Account account = accountRepository.findOneByUid(accountUid);
        List<PaidGroup> paidGroups = paidGroupRepository.findAll(Specifications
                .where(isForAccount(account))
                .and(expiresAfter(Instant.now())));
        List<Group> groups = new ArrayList<>();
        if (paidGroups != null) {
            groups = paidGroups.stream()
                    .map(PaidGroup::getGroup)
                    .distinct()
                    .sorted(Comparator.comparing(Group::getName))
                    .collect(Collectors.toList());
        }
        return groups;
    }

    @Override
    @Transactional
    public void addGroupToAccount(String accountUid, String groupUid, String addingUserUid) throws GroupAlreadyPaidForException {
        Objects.requireNonNull(groupUid);
        Objects.requireNonNull(addingUserUid);

        Group group = groupRepository.findOneByUid(groupUid);
        User addingUser = userRepository.findOneByUid(addingUserUid);

        Account account = StringUtils.isEmpty(accountUid) ? addingUser.getAccountAdministered() : accountRepository.findOneByUid(accountUid);

        if (account == null) {
            throw new IllegalArgumentException("Error! Account UID not supplied and user does not have an account");
        }

        if (!account.getAdministrators().contains(addingUser)) {
            permissionBroker.validateSystemRole(addingUser, BaseRoles.ROLE_SYSTEM_ADMIN);
        }

        if (!account.isEnabled()) {
            throw new AccountExpiredException();
        }

        if (numberGroupsLeft(account.getUid()) < 1) {
            throw new AccountLimitExceededException();
        }

        if (group == null) {
            throw new GroupNotFoundException();
        }

        if (group.isPaidFor()) {
            throw new GroupAlreadyPaidForException();
        }

        PaidGroup paidGroup = new PaidGroup(group, account, addingUser);
        paidGroupRepository.saveAndFlush(paidGroup);
        account.addPaidGroup(paidGroup);
        group.setPaidFor(true);
        storeGroupAddOrRemoveLogs(AccountLogType.GROUP_ADDED, account, group, paidGroup.getUid(), addingUser);
    }

    @Override
    @Transactional
    public int addUserCreatedGroupsToAccount(String accountUid, String userUid) {
        Objects.requireNonNull(accountUid);
        Objects.requireNonNull(userUid);

        Account account = accountRepository.findOneByUid(accountUid);
        User user = userRepository.findOneByUid(userUid);

        if (!account.getAdministrators().contains(user)) {
            throw new IllegalArgumentException("Error! Add all groups can only be called by an admin of the account");
        }

        List<Group> groups = groupRepository.findByCreatedByUserAndActiveTrueOrderByCreatedDateTimeDesc(user);
        int spaceOnAccount = groupsLeftOnAccount(account);

        List<PaidGroup> paidGroups = new ArrayList<>();
        LogsAndNotificationsBundle bundle = new LogsAndNotificationsBundle();

        for (int i = 0; i < groups.size() && (spaceOnAccount - i) > 0; i++) {
            Group group = groups.get(i);
            if (group.isPaidFor()) {
                continue;
            }

            PaidGroup paidGroup = new PaidGroup(group, account, user);
            paidGroups.add(paidGroup);
            account.addPaidGroup(paidGroup);
            group.setPaidFor(true);

            bundle.addLog(new AccountLog.Builder(account)
                    .userUid(user.getUid())
                    .accountLogType(AccountLogType.GROUP_ADDED)
                    .groupUid(group.getUid())
                    .paidGroupUid(paidGroup.getUid())
                    .description(group.getName()).build());
        }

        paidGroupRepository.save(paidGroups);
        logsAndNotificationsBroker.asyncStoreBundle(bundle);

        return paidGroups.size();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Group> candidateGroupsForAccount(String userUid, String accountUid, String filterTerm) {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(accountUid);

        User user = userRepository.findOneByUid(userUid);
        Account account = accountRepository.findOneByUid(accountUid);

        if (!account.getAdministrators().contains(user)) {
            permissionBroker.validateSystemRole(user, BaseRoles.ROLE_SYSTEM_ADMIN);
        }

        String tsQuery = FullTextSearchUtils.encodeAsTsQueryText(filterTerm == null ? "" : filterTerm, true, true);
        List<Group> userGroups = groupRepository.findByActiveAndMembershipsUserWithNameContainsText(user.getId(), tsQuery);

        logger.info("number of user groups: {}", userGroups.size());

        return userGroups.stream()
                .filter(g -> !g.isPaidFor())
                .collect(Collectors.toList());

    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAddGroupToAccount(String userUid) {
        User user = userRepository.findOneByUid(userUid);
        Account account = user.getAccountAdministered();
        return account != null && account.isEnabled() && groupsLeftOnAccount(account) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAddMultipleGroupsToOwnAccount(String userUid) {
        User user = userRepository.findOneByUid(userUid);
        if (user.getAccountAdministered() == null) {
            return false;
        } else {
            List<Group> userGroups = groupRepository.findByCreatedByUserAndActiveTrueOrderByCreatedDateTimeDesc(user);
            return userGroups != null && !userGroups.isEmpty() && userGroups.stream().anyMatch(g -> !g.isPaidFor());
        }
    }

    private int groupsLeftOnAccount(Account account) {
        return account.getMaxNumberGroups() - (int) paidGroupRepository.count(Specifications.where(
                expiresAfter(Instant.now())).and(isForAccount(account)));
    }

    @Override
    public boolean isGroupOnAccount(String groupUid) {
        return findAccountForGroup(groupUid) != null;
    }

    @Override
    @Transactional(readOnly =  true)
    public Account findAccountForGroup(String groupUid) {
        Group group = groupRepository.findOneByUid(groupUid);
        if (!group.isPaidFor()) {
            return null;
        } else {
            PaidGroup latestRecord = paidGroupRepository.findTopByGroupOrderByExpireDateTimeDesc(group);
            return latestRecord == null ? null : latestRecord.getAccount();
        }
    }

    @Override
    @Transactional
    public void removeGroupsFromAccount(String accountUid, Set<String> groupUids, String removingUserUid) {
        Objects.requireNonNull(accountUid);
        Objects.requireNonNull(groupUids);
        Objects.requireNonNull(removingUserUid);

        Account account = accountRepository.findOneByUid(accountUid);

        LogsAndNotificationsBundle bundle = new LogsAndNotificationsBundle();

        for (String groupUid : groupUids) {
            Group group = groupRepository.findOneByUid(groupUid);
            PaidGroup record = paidGroupRepository.findTopByGroupOrderByExpireDateTimeDesc(group);
            User user = userRepository.findOneByUid(removingUserUid);

            if (!account.getAdministrators().contains(user)) {
                permissionBroker.validateSystemRole(user, BaseRoles.ROLE_SYSTEM_ADMIN);
            }

            record.setExpireDateTime(Instant.now());
            record.setRemovedByUser(user);
            account.removePaidGroup(record);
            group.setPaidFor(false);

            bundle.addLog(new AccountLog.Builder(account)
                    .userUid(user.getUid())
                    .accountLogType(AccountLogType.GROUP_REMOVED)
                    .groupUid(group.getUid())
                    .paidGroupUid(record.getUid())
                    .description(group.getName()).build());

            bundle.addLog(new GroupLog(group, user, GroupLogType.GROUP_REMOVED, user.getId(), account.getUid()));
        }

        logsAndNotificationsBroker.asyncStoreBundle(bundle);
    }

    @Override
    @Transactional
    public void sendFreeFormMessage(String userUid, String groupUid, String message) {
        // for now, just let the notification async handle the group loading etc., here just check the user
        // has permission (is account admin--later, account admin and it's a paid group, with enough credit

        User user = userRepository.findOneByUid(userUid);
        Group group = groupRepository.findOneByUid(groupUid);
        Account account = user.getAccountAdministered();
        PaidGroup paidGroup = paidGroupRepository.findTopByGroupOrderByExpireDateTimeDesc(group);

        Objects.requireNonNull(user);
        Objects.requireNonNull(group);
        Objects.requireNonNull(account);
        Objects.requireNonNull(paidGroup);

        authorizeFreeFormMessageSending(user, account, group, paidGroup);

        LogsAndNotificationsBundle bundle = new LogsAndNotificationsBundle();

        String description = group.getMembers().size() + " members @ : " + account.getFreeFormCost(); // so it's recorded at cost of sending

        AccountLog accountLog = new AccountLog.Builder(account)
                .userUid(userUid)
                .accountLogType(AccountLogType.MESSAGE_SENT)
                .groupUid(groupUid)
                .paidGroupUid(paidGroup.getUid())
                .description(description)
                .build();

        bundle.addLog(accountLog);
        for (User member : group.getMembers()) {
            bundle.addNotification(new FreeFormMessageNotification(member, message, accountLog));
        }

        logsAndNotificationsBroker.storeBundle(bundle);
    }

    @Override
    @Transactional(readOnly = true)
    public int numberGroupsLeft(String accountUid) {
        Account account = accountRepository.findOneByUid(accountUid);
        return account == null ? 0 : account.isEnabled() ? groupsLeftOnAccount(account) : 0;
    }

    @Override
    @Transactional(readOnly = true)
    public int numberTodosLeftForGroup(String groupUid) {
        Group group = groupRepository.findOneByUid(groupUid);
        int todosThisMonth = (int) todoRepository.count(Specifications.where(hasGroupAsAncestor(group))
                .and(createdDateBetween(LocalDateTime.now().withDayOfMonth(1).withHour(0).toInstant(ZoneOffset.UTC), Instant.now())));

        int monthlyLimit;
        if (!group.isPaidFor()) {
            monthlyLimit = FREE_TODOS_PER_MONTH;
        } else {
            try {
                Account account = paidGroupRepository.findTopByGroupOrderByExpireDateTimeDesc(group).getAccount();
                monthlyLimit = account.getTodosPerGroupPerMonth();;
            } catch (NullPointerException e) {
                logger.warn("Error! Group is marked as paid for but has no paid group record associated to it");
                monthlyLimit = FREE_TODOS_PER_MONTH;
            }
        }

        return monthlyLimit - todosThisMonth;
    }

    @Override
    @Transactional(readOnly = true)
    public int calculateMessagesLeftThisMonth(String accountUid) {
        Account account = accountRepository.findOneByUid(accountUid);

        long messagesThisMonth = logsAndNotificationsBroker.countNotifications(Specifications.where(
                accountLogTypeIs(AccountLogType.MESSAGE_SENT))
                .and(belongsToAccount(account))
                .and(createdTimeBetween(LocalDate.now().withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC), Instant.now())));

        return Math.max(0, account.getFreeFormMessages() - (int) messagesThisMonth);
    }

    private void storeGroupAddOrRemoveLogs(AccountLogType accountLogType, Account account, Group group, String paidGroupUid, User user) {
        LogsAndNotificationsBundle bundle = new LogsAndNotificationsBundle();

        bundle.addLog(new AccountLog.Builder(account)
                .userUid(user.getUid())
                .accountLogType(accountLogType)
                .groupUid(group.getUid())
                .paidGroupUid(paidGroupUid)
                .description(group.getName()).build());

        bundle.addLog(new GroupLog(group, user, accountLogType.equals(AccountLogType.GROUP_ADDED) ?
                GroupLogType.ADDED_TO_ACCOUNT : GroupLogType.GROUP_REMOVED, user.getId(), account.getUid()));
        logsAndNotificationsBroker.storeBundle(bundle);
    }

    private void authorizeFreeFormMessageSending(User user, Account account, Group group, PaidGroup paidGroup) {
        logger.info("Authorizing message, paid group = {}, group = {}", paidGroup, group);

        if (account == null || !account.getAdministrators().contains(user)) {
            permissionBroker.validateSystemRole(user, BaseRoles.ROLE_SYSTEM_ADMIN);
        }

        if (!group.isPaidFor() || !paidGroup.isActive()) {
            throw new GroupNotPaidForException();
        }

        if (!paidGroup.getAccount().equals(account)) {
            throw new GroupAccountMismatchException();
        }

        if (group.getMemberships().size() > calculateMessagesLeftThisMonth(account.getUid())) {
            throw new AccountLimitExceededException();
        }
    }

}