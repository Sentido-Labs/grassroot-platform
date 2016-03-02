package za.org.grassroot.core.repository;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import za.org.grassroot.TestContextConfiguration;
import za.org.grassroot.core.GrassRootApplicationProfiles;
import za.org.grassroot.core.domain.*;

import javax.transaction.Transactional;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.*;

/**
 * @author Lesetse Kimwaga
 */

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = TestContextConfiguration.class)
@Transactional
@ActiveProfiles(GrassRootApplicationProfiles.INMEMORY)
public class RoleRepositoryTest {


    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @Rollback
    public void testSaveRole() throws Exception {

        Role role = new Role("CREATE_USER");
        role = roleRepository.save(role);
        assertThat(role.getId(), notNullValue());
    }

    @Test
    @Rollback
    public void testSaveWithPermissions() throws Exception {

        Permission permission = new Permission("groups.manage.remove.user");

        Role role = new Role("MANAGE_GROUPS");
        role.addPermission(permission);

        permission = permissionRepository.save(permission);

        role = roleRepository.save(role);
        assertThat(role.getId(), notNullValue());

        permission  = role.getPermissions().iterator().next();
        assertThat(permission, notNullValue());
        assertThat(permission.getId(), notNullValue());

    }

    @Test
    @Rollback
    public void testRemovePermission() throws Exception {

        Permission permission = new Permission("groups.manage.remove.user");
        Role role = new Role("MANAGE_GROUPS");
        role.addPermission(permission);
        permission = permissionRepository.save(permission);
        role = roleRepository.save(role);
        assertNotNull(role.getId());
        role.getPermissions().remove(permission);
        Role rolePersisted = roleRepository.save(role);
        assertThat(rolePersisted.getId(), is(role.getId()));
        assertThat(rolePersisted.getPermissions().contains(permission), is(false));

    }

    @Test
    @Rollback
    public void testDeleteRote() throws Exception {
        assertThat(roleRepository.count(), is(0L));
        Role role = roleRepository.save(new Role("CREATE_USER"));
        assertThat(roleRepository.count(), is(1L));
        roleRepository.delete(role);
        assertThat(roleRepository.count(), is(0L));
    }

    @Test
    @Rollback
    public void testSaveWithType() throws Exception {
        assertThat(roleRepository.count(), is(0L));
        Role role = new Role("ROLE_ACCOUNT_ADMIN");
        roleRepository.save(role);
        assertThat(roleRepository.count(), is(1L));
        Role roleFromDb = roleRepository.findByNameAndRoleType("ROLE_ACCOUNT_ADMIN", Role.RoleType.STANDARD).get(0);
        assertNotNull(roleFromDb);
        assertThat(roleFromDb.getName(), is(role.getName()));
    }

    @Test
    @Rollback
    public void testFindByName() throws Exception {
        assertThat(roleRepository.count(), is(0L));
        String roleName = "CREATE_USER";
        Role role = new Role(roleName);
        role = roleRepository.save(role);
        Role roleFromDb = roleRepository.findByName(roleName).get(0);
        assertNotNull(roleFromDb);
        assertThat(roleFromDb.getId(), is(role.getId()));
    }

    @Test
    @Rollback
    public void testSaveWithGroupReference() throws Exception {
        assertThat(roleRepository.count(), is(0L));
        String roleName = "ADD_MEMBER";
        User user = userRepository.save(new User("0812223456"));
        Group group = groupRepository.save(new Group("testGroup", user));
        Role role = roleRepository.save(new Role(roleName, group.getUid()));
        Role roleFromDb = roleRepository.findByNameAndGroupUid(roleName, group.getUid());
        assertNotNull(roleFromDb);
        assertThat(roleFromDb.getId(), is(role.getId()));
        assertThat(roleFromDb.getName(), is(role.getName()));
        assertThat(roleFromDb.getGroupUid(), is(role.getGroupUid()));
    }

    @Test
    @Rollback
    public void testGroupAssignment() throws Exception {
        assertThat(roleRepository.count(), is(0L));
        User user = userRepository.save(new User("0801110000"));
        Group group1 = groupRepository.save(new Group("gc1", user));

        Set<Role> group1roles = roleRepository.findByGroupUid(group1.getUid());

        assertThat(group1roles.size(), is(3));
    }

    @Test
    @Rollback
    public void testGroupAssignmentAfterConstruction() throws Exception {
        assertThat(roleRepository.count(), is(0L));

        User user = userRepository.save(new User("0811110001"));
        Group group = groupRepository.save(new Group("test Group", user));

        assertThat(roleRepository.count(), is(3L)); // check doesn't duplicate

        Role roleFromDb = roleRepository.findByNameAndGroupUid(BaseRoles.ROLE_GROUP_ORGANIZER, group.getUid());
        assertNotNull(roleFromDb);
        assertThat(roleFromDb.getGroupUid(), is(group.getUid()));
    }
}
