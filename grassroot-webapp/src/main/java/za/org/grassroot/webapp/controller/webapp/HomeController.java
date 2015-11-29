package za.org.grassroot.webapp.controller.webapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import za.org.grassroot.core.domain.Event;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.core.dto.GroupTreeDTO;
import za.org.grassroot.core.util.AuthenticationUtil;
import za.org.grassroot.services.EventManagementService;
import za.org.grassroot.services.GroupManagementService;
import za.org.grassroot.services.UserManagementService;
import za.org.grassroot.webapp.controller.BaseController;
import za.org.grassroot.webapp.model.web.GroupViewNode;
import za.org.grassroot.webapp.model.web.GroupViewNodeSql;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Lesetse Kimwaga
 */
@Controller
public class HomeController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(HomeController.class);

    @Autowired
    GroupManagementService groupManagementService;

    @Autowired
    EventManagementService eventManagementService;

    @Autowired
    AuthenticationUtil authenticationUtil;

    @Autowired
    SigninController signinController;

    private long prevRoot = 0;
    private int level = 0;
    private GroupViewNodeSql nodeSql = new GroupViewNodeSql();
    private GroupViewNodeSql subNodeSql = new GroupViewNodeSql();
    private int nodeCount = 0;
    private int idx = 0;
    private GroupTreeDTO node = null;
    private List<GroupTreeDTO> treeList = null;


    @RequestMapping("/")
    public ModelAndView getRootPage(Model model, HttpServletRequest request) {
        log.debug("Getting home page");
        if (signinController.isRememberMeAuthenticated()) {
            return signinController.autoLogonUser(request, model);
        }
        if (signinController.isAuthenticated()) {
            model.addAttribute("userGroups", groupManagementService.getActiveGroupsPartOf(getUserProfile()));
            return new ModelAndView("home", model.asMap());
        }

        return new ModelAndView("index", model.asMap());
    }


    @RequestMapping("/home")
    public String getHomePage(Model model, @ModelAttribute("currentUser") UserDetails userDetails) {

        authenticationUtil.debugAuthentication();

        User user = userManagementService.fetchUserByUsername(userDetails.getUsername());

        /*
         Recursive construction in the view node will turn each of these into a tree with a root node as the top level
         group. There may be a more efficient way to do this than the groupManagement call (and/or optimizing within it
         */

        List<Group> topLevelGroups = groupManagementService.getActiveTopLevelGroups(user);
        List<GroupViewNode> groupViewNodes = new ArrayList<>();
        log.info("getHomePage...tree starting...");
        for (Group group : topLevelGroups) {
            log.debug("Creating a group node from group: " + group.getGroupName());
            groupViewNodes.add(new GroupViewNode(group, user, groupManagementService));
        }
        log.info("getHomePage...tree ending...");

        // start of SQL tree

        log.info("getHomePage...NEW tree starting...");
        treeList = groupManagementService.getGroupsMemberOfTree(user.getId());

        List<GroupViewNodeSql> groupViewNodeSqls = new ArrayList<>();
        nodeCount = treeList.size();
        idx = 0;

        while (idx < nodeCount) {

            node = treeList.get(idx++);
            //log.info("getHomePage..." + idx + "...group..." + node.getGroupName());

            if (node.getRoot() != prevRoot) { // finish of last root and start a new one
                if (prevRoot != 0) { // not the first record
                    groupViewNodeSqls.add(nodeSql);
                }
                level = 0;
                nodeSql = new GroupViewNodeSql(node.getGroupName(), level, node.getParentId());
                prevRoot = node.getRoot();
                continue;
            }
            // recursive call for subnodes - not root
            subNodeSql = recursiveTreeSubnodes(new GroupViewNodeSql(node.getGroupName(), ++level, node.getParentId()));
            nodeSql.getSubgroups().add(subNodeSql);
        }
        // add the last record
        groupViewNodeSqls.add(nodeSql);


        log.info("getHomePage...NEW tree ending...");

        // end of SQL tree

        model.addAttribute("userGroups", groupManagementService.getActiveGroupsPartOf(user));
        model.addAttribute("groupTrees", groupViewNodes);
        model.addAttribute("groupTreesSql", groupViewNodeSqls);

        // get lists of outstanding RSVPs and votes
        List<Event> meetingsToRsvp = eventManagementService.getOutstandingRSVPForUser(user);
        List<Event> votesToAnswer = eventManagementService.getOutstandingVotesForUser(user);

        model.addAttribute("meetingRsvps", meetingsToRsvp);
        model.addAttribute("votesToAnswer", votesToAnswer);

        return "home";
    }


    private GroupViewNodeSql recursiveTreeSubnodes(GroupViewNodeSql parentNode) {

        // see if there are more records
        while (idx < nodeCount ) {
            node = treeList.get(idx++);
            //log.info("recursiveTreeSubnodes..." + idx + "...group..." + node.getGroupName() + "...parent..." + node.getParentId());
            if (node.getRoot() == prevRoot) {

                if (node.getParentId() == parentNode.getParentId()) {
                    parentNode.getSubgroups().add(new GroupViewNodeSql(node.getGroupName(), level, node.getParentId()));
                } else {
                    GroupViewNodeSql childNode = recursiveTreeSubnodes(new GroupViewNodeSql(node.getGroupName(),++level,node.getParentId()));
                    parentNode.getSubgroups().add(childNode);
                }

            } else {
                // if we get here we to to carry on processing on the outer loop so decrement counter
                idx--;
                break;
            }

        }
        return parentNode;

    }

    private List<Event> getConsolidatedGroupEvents(List<Group> groups) {
        List<Event> groupEvents = new ArrayList<>();
        for (Group group : groups) {

            List<Event> events = eventManagementService.findByAppliesToGroup(group);
            if (!events.isEmpty()) {
                groupEvents.addAll(events);
            }
        }
        return groupEvents;
    }

}
