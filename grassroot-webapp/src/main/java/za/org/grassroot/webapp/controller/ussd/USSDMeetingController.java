package za.org.grassroot.webapp.controller.ussd;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import za.org.grassroot.core.domain.Event;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.webapp.controller.ussd.menus.USSDMenu;
import za.org.grassroot.webapp.model.ussd.AAT.Request;

import java.net.URISyntaxException;
import java.util.*;

import static org.springframework.web.bind.annotation.RequestMethod.GET;

/**
 * @author luke on 2015/08/14.
 */

@RequestMapping(method = GET, produces = MediaType.APPLICATION_XML_VALUE)
@RestController
public class USSDMeetingController extends USSDController {

    /**
     * Meeting organizer menus
     * todo: Various forms of validation and checking throughout
     * todo: Think of a way to pull together the common method set up stuff (try to load user, get next key)
     * todo: Make the prompts also follow the sequence somehow (via a map of some sort, likely)
     * todo: Replace "meeting" as the event "name" with a meeting subject
     */

    private static final String keyGroup = "group", keySubject="subject", keyDate = "date", keyTime = "time", keyPlace = "place", keySend = "send";
    private static final String mtgPath = USSD_BASE +MTG_MENUS, meetingName = "Meeting"; // this is what needs to be replaced

    private static final List<String> menuSequence = Arrays.asList(START_KEY, keySubject, keyTime, keyPlace, keySend);

    private String nextMenuKey(String currentMenuKey) {
        return menuSequence.get(menuSequence.indexOf(currentMenuKey) + 1);
    }

    /*
    Opening menu. As of now, first prompt is to create a group.
     */
    @RequestMapping(value = mtgPath + START_KEY)
    @ResponseBody
    public Request meetingOrg(@RequestParam(value=PHONE_PARAM, required=true) String inputNumber) throws URISyntaxException {

        User sessionUser;
        try { sessionUser = userManager.findByInputNumber(inputNumber); }
        catch (NoSuchElementException e) { return noUserError; }

        Event meetingToCreate = eventManager.createEvent("", sessionUser);

        USSDMenu thisMenu = askForGroup(sessionUser, meetingToCreate.getId(), nextMenuKey(START_KEY));
        return menuBuilder(thisMenu);
    }

    private USSDMenu askForGroup(User sessionUser, Long eventId, String keyNext) throws URISyntaxException {
        USSDMenu groupMenu = new USSDMenu("");
        if (sessionUser.getGroupsPartOf().isEmpty()) {
            groupMenu.setFreeText(true);
            groupMenu.setPromptMessage(getMessage(MTG_KEY, START_KEY, PROMPT + ".new-group", sessionUser));
            groupMenu.setNextURI(MTG_MENUS + keyGroup + EVENTID_URL + eventId);
        } else {
            String promptMessage = getMessage(MTG_KEY, START_KEY, PROMPT + ".has-group", sessionUser);
            String existingGroupNext = MTG_MENUS + keyNext + EVENTID_URL + eventId + "&" + PASSED_FIELD + "=" + keyGroup;
            String newGroupNext = MTG_MENUS + keyGroup + EVENTID_URL + eventId;
            groupMenu = userGroupMenu(sessionUser, promptMessage, existingGroupNext, newGroupNext);
        }
        return groupMenu;
    }

    /*
    The group creation menu, the most complex of them. Since we only ever arrive here from askForGroup menu, we can
    name the parameters more naturally than the abstract/generic look up in the other menus.
    There are three cases for the user having arrived here:
        (1) the user had no groups before, and was asked to enter a set of numbers to create a group
        (2) the user had other groups, but selected "create new group" on the previous menu
        (3) the user has entered some numbers, and is being asked for more
     */

    @RequestMapping(value = mtgPath + keyGroup)
    @ResponseBody
    public Request createGroup(@RequestParam(value=PHONE_PARAM, required=true) String inputNumber,
                               @RequestParam(value=EVENT_PARAM, required=true) Long eventId,
                               @RequestParam(value=GROUP_PARAM, required=false) Long groupId,
                               @RequestParam(value=TEXT_PARAM, required=false) String userResponse) throws URISyntaxException {

        String keyNext = nextMenuKey(START_KEY);

        User sessionUser;
        Group groupToInvite;

        try { sessionUser = userManager.findByInputNumber(inputNumber); }
        catch (NoSuchElementException e) { return noUserError; }

        Event meetingToCreate = eventManager.loadEvent(eventId);

        USSDMenu thisMenu = new USSDMenu("");
        thisMenu.setFreeText(true);

        // so, what we do, is selecting a group number takes you to the next one (subject), but selecting new group, or if has no group, then come here

        /* if (groupId == null) { // case 1, a group of numbers entered, so next menu is add something to event
            groupToInvite = groupManager.createNewGroup(sessionUser, splitPhoneNumbers(userResponse, " ").get("valid"));
            meetingToCreate = eventManager.setGroup(eventId, groupToInvite.getId());
            thisMenu.setPromptMessage(getMessage(MTG_KEY, keyGroup, PROMPT + ".next2", sessionUser));
            thisMenu.setNextURI(MTG_MENUS + keyNext + EVENTID_URL + eventId + "&" + PASSED_FIELD + "=" + keyDate);
        } else if (groupId == 0) { // case 2, "create a new group" selected, so next menu is cycling back to this one
            thisMenu.setPromptMessage(getMessage(MTG_KEY, keyGroup, PROMPT + ".new-group", sessionUser));
            thisMenu.setNextURI(MTG_MENUS + keyGroup + EVENTID_URL + eventId);
        } else { // case 3, an existing group selected, so ask for and pass forward the field requested

        } */

        if (userResponse.trim().equals("0")) { // stop asking for numbers and go on to naming the group
            thisMenu.setPromptMessage(getMessage(MTG_KEY, keyGroup + DO_SUFFIX, PROMPT + ".done", sessionUser));
            thisMenu.setNextURI(MTG_KEY + keyGroup + DO_SUFFIX + GROUPID_URL + groupId); // reusing the rename function
        } else {
            Map<String, List<String>> splitPhoneNumbers = splitPhoneNumbers(userResponse, " ");
            if (groupId == null) { // creating a new group, process numbers and ask for more
                Group createdGroup = groupManager.createNewGroup(sessionUser, splitPhoneNumbers.get("valid"));
                thisMenu = numberEntryPrompt(createdGroup.getId(), "created", sessionUser, splitPhoneNumbers.get("error"));
            } else { // adding to a group, process numbers and ask to fix errors or to stop
                groupManager.addNumbersToGroup(groupId, splitPhoneNumbers.get("valid"));
                thisMenu = numberEntryPrompt(groupId, "added", sessionUser, splitPhoneNumbers.get("error"));
            }
        }

        return menuBuilder(thisMenu);

    }

    public USSDMenu numberEntryPrompt(Long groupId, String promptKey, User sessionUser, List<String> errorNumbers) {

        USSDMenu thisMenu = new USSDMenu("");
        thisMenu.setFreeText(true);

        if (errorNumbers.size() == 0) {
            thisMenu.setPromptMessage(getMessage(MTG_KEY, keyGroup, PROMPT + "." + promptKey, sessionUser));
        } else {
            // assemble the error menu
            String listErrors = String.join(", ", errorNumbers);
            String promptMessage = getMessage(MTG_KEY, keyGroup, PROMPT_ERROR, listErrors, sessionUser);
            thisMenu.setPromptMessage(promptMessage);
        }

        thisMenu.setNextURI(MTG_KEY + keyGroup + GROUPID_URL + groupId); // loop back to group menu
        return thisMenu;

    }

    /*
    The subsequent menus are more straightforward, each filling in a part of the event data structure
    The auxiliary function at the end and the passing of the parameter name means we can shuffle these at will
    Not collapsing them into one function as may want to convert some from free text to a menu of options later
    Though even then, may be able to collapse them -- but then need to access which URL within method
     */

    // todo change GROUP_PARAM to TEXT_PARAM in the parameter mapping once fix the upstream method

    @RequestMapping(value = mtgPath + keySubject)
    @ResponseBody
    public Request getSubject(@RequestParam(value=PHONE_PARAM, required=true) String inputNumber,
                              @RequestParam(value=EVENT_PARAM, required=true) Long eventId,
                              @RequestParam(value=PASSED_FIELD, required=true) String passedValueKey,
                              @RequestParam(value=GROUP_PARAM, required = true) String passedValue) throws URISyntaxException {

        String keyNext = nextMenuKey(keySubject); // skipped for the moment, like keyDate
        User sessionUser = userManager.findByInputNumber(inputNumber);
        Event meetingToCreate = updateEvent(eventId, passedValueKey, passedValue);
        String promptMessage = getMessage(MTG_KEY, keySubject, PROMPT, sessionUser);

        USSDMenu thisMenu = new USSDMenu(promptMessage, MTG_MENUS + keyNext + EVENTID_URL + eventId + "&" + PASSED_FIELD + "=" + keySubject);
        return menuBuilder(thisMenu);

    }

    @RequestMapping(value = mtgPath + keyDate)
    @ResponseBody
    public Request getDate(@RequestParam(value=PHONE_PARAM, required=true) String inputNumber,
                           @RequestParam(value=EVENT_PARAM, required=true) Long eventId,
                           @RequestParam(value=PASSED_FIELD, required=true) String passedValueKey,
                           @RequestParam(value=TEXT_PARAM, required = true) String passedValue) throws URISyntaxException {

        // todo: create some default options for the next 3 days, for date

        String keyNext = nextMenuKey(keyDate);
        User sessionUser = userManager.findByInputNumber(inputNumber);
        Event meetingToCreate = updateEvent(eventId, passedValueKey, passedValue);
        String promptMessage = getMessage(MTG_KEY, keyDate, PROMPT, sessionUser);

        USSDMenu thisMenu = new USSDMenu(promptMessage, MTG_MENUS + keyNext + EVENTID_URL + eventId + "&" + PASSED_FIELD + "=" + keyDate);
        return menuBuilder(thisMenu);

    }

    @RequestMapping(value = mtgPath + keyTime)
    @ResponseBody
    public Request getTime(@RequestParam(value=PHONE_PARAM, required=true) String inputNumber,
                           @RequestParam(value=EVENT_PARAM, required=true) Long eventId,
                           @RequestParam(value=PASSED_FIELD, required=false) String passedValueKey,
                           @RequestParam(value=TEXT_PARAM, required=true) String passedValue) throws URISyntaxException {

        String keyNext = nextMenuKey(keyTime);
        User sessionUser = userManager.findByInputNumber(inputNumber);
        Event meetingToCreate = updateEvent(eventId, passedValueKey, passedValue);
        String promptMessage = getMessage(MTG_KEY, keyTime, PROMPT, sessionUser);

        return menuBuilder(new USSDMenu(promptMessage, MTG_MENUS + keyNext + EVENTID_URL + eventId + "&" + PASSED_FIELD + "=" + keyTime));

    }

    @RequestMapping(value = mtgPath + keyPlace)
    @ResponseBody
    public Request getPlace(@RequestParam(value=PHONE_PARAM, required=true) String inputNumber,
                            @RequestParam(value=EVENT_PARAM, required=true) Long eventId,
                            @RequestParam(value=PASSED_FIELD, required=true) String passedValueKey,
                            @RequestParam(value=TEXT_PARAM, required=true) String passedValue) throws URISyntaxException {

        // todo: add a lookup of group default places
        // todo: add error and exception handling

        String keyNext = nextMenuKey(keyPlace);
        User sessionUser = userManager.findByInputNumber(inputNumber);
        Event meetingToCreate = updateEvent(eventId, passedValueKey, passedValue);
        String promptMessage = getMessage(MTG_KEY, keyPlace, PROMPT, sessionUser);

        return menuBuilder(new USSDMenu(promptMessage, MTG_MENUS + keyNext + EVENTID_URL + eventId + "&" + PASSED_FIELD + "=" + keyPlace));
    }

    /*
    Finally, do the last update, assemble a text message and send it out -- most of this needs to move to the messaging layer
     */

    @RequestMapping(value = mtgPath + keySend)
    @ResponseBody
    public Request sendMessage(@RequestParam(value=PHONE_PARAM, required=true) String inputNumber,
                               @RequestParam(value=EVENT_PARAM, required=true) Long eventId,
                               @RequestParam(value=PASSED_FIELD, required=true) String passedValueKey,
                               @RequestParam(value=TEXT_PARAM, required=true) String passedValue) throws URISyntaxException {

        // todo: various forms of error handling here (e.g., non-existent group, invalid users, etc)
        // todo: store the response from the SMS gateway and use it to state how many messages successful
        // todo: split up the URI into multiple if it gets >2k chars (will be an issue when have 20+ person groups)
        // todo: add shortcode for RSVP reply

        User sessionUser;
        try { sessionUser = userManager.findByInputNumber(inputNumber); }
        catch (Exception e) { return noUserError; }

        Event meetingToSend = updateEvent(eventId, passedValueKey, passedValue);
        Group groupToMessage = meetingToSend.getAppliesToGroup();
        List<User> usersToMessage = groupToMessage.getGroupMembers();

        String groupName = (groupToMessage.hasName()) ? ("of group, " + groupToMessage.getGroupName() + ", ") : "";

        String msgText = "From " + sessionUser.getName("") + ": Meeting called " + groupName + "on " + meetingToSend.getDayOfEvent()
                + ", at time " + meetingToSend.getTimeOfEvent() + " and place " + meetingToSend.getEventLocation();

        System.out.println(msgText);

        RestTemplate sendGroupSMS = new RestTemplate();
        UriComponentsBuilder sendMsgURI = UriComponentsBuilder.newInstance().scheme("https").host(smsHost);
        sendMsgURI.path("send/").queryParam("username", smsUsername).queryParam("password", smsPassword);

        for (int i = 1; i <= usersToMessage.size(); i++) {
            sendMsgURI.queryParam("number" + i, usersToMessage.get(i-1).getPhoneNumber());
            sendMsgURI.queryParam("message" + i, msgText);
        }

        String messageResult = sendGroupSMS.getForObject(sendMsgURI.build().toUri(), String.class);
        System.out.println(messageResult);

        return menuBuilder(new USSDMenu(getMessage(MTG_KEY, keySend, PROMPT, sessionUser), optionsHomeExit(sessionUser)));
    }

    /*
     * Auxiliary functions to help with passing parameters around, to allow for flexibility in menu order
     * Possibly move to the higher level controller class
    */

    private Event updateEvent(Long eventId, String lastMenuKey, String passedValue) {
        Event eventToReturn;
        switch(lastMenuKey) {
            case keySubject:
                eventToReturn = eventManager.setSubject(eventId, passedValue);
                break;
            case keyDate:
                eventToReturn = eventManager.setDay(eventId, passedValue);
                break;
            case keyTime:
                eventToReturn = eventManager.setTime(eventId, passedValue);
                break;
            case keyPlace:
                eventToReturn = eventManager.setLocation(eventId, passedValue);
                break;
            case keyGroup:
                eventToReturn = eventManager.setGroup(eventId, Long.parseLong(passedValue));
                break;
            default:
                eventToReturn = eventManager.loadEvent(eventId);
        }
        return eventToReturn;
    }



}
