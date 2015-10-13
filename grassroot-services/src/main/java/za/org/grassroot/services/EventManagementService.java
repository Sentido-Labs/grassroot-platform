package za.org.grassroot.services;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import za.org.grassroot.core.domain.Event;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.core.enums.EventRSVPResponse;
import za.org.grassroot.core.enums.EventType;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author Lesetse Kimwaga
 */
public interface EventManagementService {

    Event createEvent(String name, User createdByUser, Group appliesToGroup, boolean includeSubGroups, boolean rsvpRequired);

    Event createEvent(String name, User createdByUser, Group appliesToGroup, boolean includeSubGroups);

    public Event createEvent(String name, User createdByUser, Group group);

    Event createEvent(String name, Long createdByUserId, Long appliesToGroupId, boolean includeSubGroups);

    public Event createEvent(String name, Long createdByUserId, Long groupId);

    public Event createEvent(String name, User createdByUser);

    public Event createMeeting(User createdByUser);

    public Event loadEvent(Long eventId);

    public Event getLastCreatedEvent(User creatingUser);

    public Event setSubject(Long eventId, String subject);

    public Event setGroup(Long eventId, Long groupId);

    public Event setLocation(Long eventId, String location);

    public Event setDateTimeString(Long eventId, String dateTimeString);

    public Event setEventTimestamp(Long eventId, Timestamp eventDateTime);

    public Event updateEvent(Event eventToUpdate);

    public Event cancelEvent(Long eventId);

    List<Event> findByAppliesToGroup(Group appliesToGroup);

    List<Event> findByAppliesToGroupAndStartingAfter(Group group, Date date);

    List<Event> getUpcomingEvents(Group group);

    List<User> getListOfUsersThatRSVPYesForEvent(Event event);

    List<User> getListOfUsersThatRSVPNoForEvent(Event event);

    Map<User, EventRSVPResponse> getRSVPResponses(Event event);

    List<Event> getOutstandingRSVPForUser(Long userId);

    List<Event> getOutstandingRSVPForUser(User user);

    List<Event> getUpcomingEventsForGroupAndParentGroups(Group group);

    List<Event> getUpcomingEventsUserCreated(User requestingUser);

    List<Event> getUpcomingEvents(User requestingUser);

    boolean hasUpcomingEvents(User requestingUser);

    String[] populateNotificationFields(Event event);

    Map<String, String> getEventDescription(Event event);

    Map<String, String> getEventDescription(Long eventId);

    int getNumberInvitees(Event event);

}
