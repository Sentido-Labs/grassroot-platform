package za.org.grassroot.webapp.controller.rest;


import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import za.org.grassroot.core.domain.Event;
import za.org.grassroot.core.enums.EventType;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Created by paballo on 2016/02/18.
 */
public class AATIncomingSMSControllerTest extends RestAbstractUnitTest {

    private static final String path = "/sms/";
    private Event meeting;

    @InjectMocks
    private AATIncomingSMSController aatIncomingSMSController;

    @Before
    public void setUp() {
        mockMvc =   MockMvcBuilders.standaloneSetup(aatIncomingSMSController).build();
    }

    @Test
    public void receiveSMSShouldWorkWithValidInput() throws Exception{

        meeting = meetingEvent;
        meeting.setRsvpRequired(true);
        List<Event> meetings = Collections.singletonList(meeting);

        when(userManagementServiceMock.loadOrSaveUser(testUserPhone)).thenReturn(sessionTestUser);
        when(eventBrokerMock.userHasResponsesOutstanding(sessionTestUser, EventType.VOTE)).thenReturn(false);
        when(eventBrokerMock.userHasResponsesOutstanding(sessionTestUser, EventType.MEETING)).thenReturn(true);
        when(eventBrokerMock.getOutstandingResponseForUser(sessionTestUser, EventType.MEETING)).thenReturn(meetings);

        mockMvc.perform(get(path+"incoming").param("fn", testUserPhone).param("ms", "yes"))
                .andExpect(status().isOk());

        verify(userManagementServiceMock).loadOrSaveUser(testUserPhone);
        verify(eventBrokerMock).userHasResponsesOutstanding(sessionTestUser, EventType.MEETING);
        verify(eventBrokerMock).userHasResponsesOutstanding(sessionTestUser, EventType.VOTE);
        verify(eventBrokerMock).getOutstandingResponseForUser(sessionTestUser, EventType.MEETING);
    }

    @Test
    public void receiveSMSShouldWorkWithInvalidInput() throws Exception{
        // todo : check calls to message service & sms service
        when(userManagementServiceMock.loadOrSaveUser(testUserPhone)).thenReturn(sessionTestUser);
        mockMvc.perform(get(path+"incoming").param("fn",testUserPhone).param("ms", "yebo"))
                .andExpect(status().isOk());
        verify(userManagementServiceMock, times(1)).loadOrSaveUser(testUserPhone);
        verifyNoMoreInteractions(userManagementServiceMock);
        verifyNoMoreInteractions(eventManagementServiceMock);

    }


}
