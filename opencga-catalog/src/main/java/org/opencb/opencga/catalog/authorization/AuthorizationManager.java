package org.opencb.opencga.catalog.authorization;


//import java.security.acl.Acl;

import org.opencb.datastore.core.QueryResult;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.models.*;

import java.util.*;

/**
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public interface AuthorizationManager {

    User.Role getUserRole(String userId) throws CatalogException;

    Acl getProjectACL(String userId, int projectId) throws CatalogException;

    QueryResult setProjectACL(int projectId, Acl acl, String sessionId) throws CatalogException;

    Acl getStudyACL(String userId, int studyId) throws CatalogException;

    QueryResult setStudyACL(int studyId, Acl acl, String sessionId) throws CatalogException;

    Acl getFileACL(String userId, int fileId) throws CatalogException;

    QueryResult setFileACL(int fileId, Acl acl, String sessionId) throws CatalogException;

    Acl getSampleACL(String userId, int sampleId) throws CatalogException;

    QueryResult setSampleACL(int sampleId, Acl acl, String sessionId);

    void filterProjects(String userId, List<Project> projects) throws CatalogException;

    void filterStudies(String userId, Acl projectAcl, List<Study> studies) throws CatalogException;

    void filterFiles(String userId, Acl studyAcl, List<File> files) throws CatalogException;

    static List<Group> getDefaultGroups(Collection<String> adminUsers) {
        return Arrays.asList(
                new Group("admin", new ArrayList<>(adminUsers), new StudyPermissions(true, true, true, true, true, true)),
                new Group("dataManager", Collections.emptyList(), new StudyPermissions(true, true, true, true, true, false)),
                new Group("members", Collections.emptyList(), new StudyPermissions(true, false, false, false, false, false)));
    }

    Group getGroupBelonging(int studyId, String userId) throws CatalogException;
//    Group createGroup(int studyId, String groupId, GroupPermissions groupPermissions, String sessionId) throws CatalogException;
//    void deleteGroup(int studyId, String groupId, String sessionId) throws CatalogException;
    QueryResult<Group> addMember(int studyId, String groupId, String userId, String sessionId) throws CatalogException;
    QueryResult<Group> removeMember(int studyId, String groupId, String userId, String sessionId) throws CatalogException;
}
