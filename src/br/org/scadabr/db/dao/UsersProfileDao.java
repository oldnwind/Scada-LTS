package br.org.scadabr.db.dao;

import br.org.scadabr.api.exception.DAOException;
import br.org.scadabr.vo.permission.ViewAccess;
import br.org.scadabr.vo.permission.WatchListAccess;
import br.org.scadabr.vo.usersProfiles.UsersProfileVO;
import com.serotonin.mango.Common;
import com.serotonin.mango.db.dao.BaseDao;
import com.serotonin.mango.db.dao.ViewDao;
import com.serotonin.mango.db.dao.WatchListDao;
import com.serotonin.mango.view.View;
import com.serotonin.mango.vo.User;
import com.serotonin.mango.vo.WatchList;
import com.serotonin.mango.vo.permission.DataPointAccess;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.scada_lts.dao.DAO;
import org.scada_lts.mango.service.UserService;
import org.scada_lts.mango.service.ViewService;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.ListIterator;

public class UsersProfileDao extends BaseDao {
    public Log LOG = LogFactory.getLog(UsersProfileDao.class);

    private static List<UsersProfileVO> currentProfileList = null;

    private static final String PROFILES_SELECT = "select u.id, u.name, u.xid "
            + "from usersProfiles u";

    private static final String PROFILES_INSERT = "insert into usersProfiles (xid, name) values (?, ?)";

    private static final String PROFILES_UPDATE = "update usersProfiles set "
            + "  name=? " + "where id=?";

    private static final String PROFILES_DELETE = "delete from usersProfiles where id = (?)";

    private WatchListDao watchlistDao = new WatchListDao();

    private ViewDao viewDao = new ViewDao();
    private UserService userService = new UserService();
    private ViewService viewService = new ViewService();


    public List<UsersProfileVO> getUsersProfiles() {
        if (currentProfileList == null) {

            currentProfileList = DAO.getInstance().getJdbcTemp().query(PROFILES_SELECT + " order by u.name",
                    new UsersProfilesRowMapper());

            populateUserProfilePermissions(currentProfileList);
        }
        return currentProfileList;
    }

    public UsersProfileVO getUserProfileByName(String name) {

        ListIterator<UsersProfileVO> iterator = currentProfileList
                .listIterator();
        while (iterator.hasNext()) {
            UsersProfileVO iterProfile = iterator.next();
            LOG.debug(iterProfile.getName() + ' ' + iterProfile.getXid());
            if (iterProfile.getName() == name) {
                return iterProfile;
            }
        }
        LOG.debug("Profile not Found!");
        return null;
    }

    public UsersProfileVO getUserProfileById(int id) {

        ListIterator<UsersProfileVO> iterator = currentProfileList
                .listIterator();
        while (iterator.hasNext()) {
            UsersProfileVO iterProfile = iterator.next();
            LOG.debug(iterProfile.getName() + ' ' + iterProfile.getXid());
            if (iterProfile.getId() == id) {
                return iterProfile;
            }
        }
        LOG.debug("Profile not Found!");
        return null;
    }

    public UsersProfileVO getUserProfileByXid(String xid) {

        ListIterator<UsersProfileVO> iterator = currentProfileList
                .listIterator();
        while (iterator.hasNext()) {
            UsersProfileVO iterProfile = iterator.next();
            LOG.debug(iterProfile.getName() + ' ' + iterProfile.getXid());
            if (iterProfile.getXid() == xid) {
                return iterProfile;
            }
        }
        LOG.debug("Profile not Found!");
        return null;
    }

    public void saveUsersProfile(UsersProfileVO profile) throws DAOException {
        if (profileExistsWithThatName(profile)
                && profile.getId() == Common.NEW_ID) {
            throw new DAOException();
        }

        saveUsersProfileWithoutNameConstraint(profile);
    }

    private boolean profileExistsWithThatName(UsersProfileVO profile) {
        return getUserProfileByName(profile.getName()) != null;
    }

    public void saveUsersProfileWithoutNameConstraint(UsersProfileVO profile)
            throws DAOException {
        if (profile.getName() == null
                || profile.getName().replaceAll("\\s+", "").isEmpty()) {
            throw new DAOException();
        }

        if (profile.getXid() == null) {
            profile.setXid(generateUniqueXid(UsersProfileVO.XID_PREFIX,
                    "usersProfiles"));
        }

        if (profile.getId() == Common.NEW_ID) {
            insertProfile(profile);
        } else {
            updateProfile(profile);
        }
    }

    public void updateProfile(UsersProfileVO profile) {

        DAO.getInstance().getJdbcTemp().update(PROFILES_UPDATE,
                profile.getName(), profile.getId());


        List<Integer> usersIds = DAO.getInstance().getJdbcTemp().queryForList(USERS_PROFILES_USERS_SELECT
                        + " where u.userProfileId=?", new Object[]{profile.getId()},
                Integer.class);

        UserService userService = new UserService();

        for (Integer userId : usersIds) {
            User profileUser = userService.getUser(userId);
            profile.apply(profileUser);
            userService.saveUser(profileUser);
            this.updateUsersProfile(profile);
        }

        saveRelationalData(profile);

    }

    public void updateUsersProfile(UsersProfileVO profile) {
        if (profile.retrieveLastAppliedUser() != null) {

            DAO.getInstance().getJdbcTemp().update("delete from usersUsersProfiles where userId=?",
                    profile.retrieveLastAppliedUser().getId());


            DAO.getInstance().getJdbcTemp().update("insert into usersUsersProfiles (userProfileId, userId) values (?,?)",
                    profile.getId(),
                    profile.retrieveLastAppliedUser().getId());

        }

        for (WatchList watchlist : profile.retrieveWatchlists()) {
            watchlistDao.saveWatchList(watchlist);
        }

        for (View view : profile.retrieveViews()) {
            viewService.saveView(view);
        }

        ListIterator<UsersProfileVO> iterator = currentProfileList
                .listIterator();
        while (iterator.hasNext()) {
            UsersProfileVO iterProfile = iterator.next();
            LOG.debug(iterProfile.getName() + ' ' + iterProfile.getXid());
            if (iterProfile.getId() == profile.getId()) {
                iterator.set(profile);
            }
        }

    }

    private void insertProfile(UsersProfileVO profile) {

        DAO.getInstance().getJdbcTemp().update(PROFILES_INSERT, profile.getXid(), profile.getName());
        profile.setId(DAO.getInstance().getId());

        saveRelationalData(profile);

        currentProfileList.add(profile);
    }

    private class UsersProfilesRowMapper implements
            RowMapper<UsersProfileVO> {

        public UsersProfilesRowMapper() {
        }

        ;

        public UsersProfileVO mapRow(ResultSet rs, int rowNum)
                throws SQLException {
            UsersProfileVO edt = new UsersProfileVO();
            edt.setId(rs.getInt(1));
            edt.setName(rs.getString(2));
            edt.setXid(rs.getString(3));
            return edt;
        }
    }

    private static final String SELECT_DATA_SOURCE_PERMISSIONS = "select dataSourceId from dataSourceUsersProfiles where userProfileId=?";
    private static final String SELECT_DATA_POINT_PERMISSIONS = "select dataPointId, permission from dataPointUsersProfiles where userProfileId=?";
    private static final String SELECT_WATCHLIST_PERMISSIONS = "select watchlistId, permission from watchListUsersProfiles where userProfileId=?";
    private static final String SELECT_VIEW_PERMISSIONS = "select viewId, permission from viewUsersProfiles where userProfileId=?";
    private static final String USERS_PROFILES_SELECT = "select userProfileId, userId from usersUsersProfiles u";
    private static final String USERS_PROFILES_USERS_SELECT = "select userId from usersUsersProfiles u";

    private void populateUserProfilePermissions(UsersProfileVO profile) {
        if (profile == null) {
            return;
        }

        LOG.debug("populateDataSources");
        populateDataSources(profile);
        LOG.debug("populateDatapoints");
        populateDatapoints(profile);
        LOG.debug("populateWatchlists");
        populateWatchlists(profile);
        LOG.debug("populateViews");
        populateViews(profile);
        LOG.debug("populateUsers");
        populateUsers(profile);
        LOG.debug("end");

    }

    private void populateUsers(UsersProfileVO profile) {
        profile.defineUsers(DAO.getInstance().getJdbcTemp().queryForList(USERS_PROFILES_USERS_SELECT
                        + " where userProfileId=?", new Object[]{profile.getId()},
                Integer.class));
    }

    private void populateWatchlists(UsersProfileVO profile) {

        profile.setWatchlistPermissions(DAO.getInstance().getJdbcTemp().query(SELECT_WATCHLIST_PERMISSIONS,
                new Object[]{profile.getId()},
                new RowMapper<WatchListAccess>() {
                    public WatchListAccess mapRow(ResultSet rs, int rowNum)
                            throws SQLException {
                        WatchListAccess a = new WatchListAccess(rs.getInt(1),
                                rs.getInt(2));
                        return a;
                    }
                }));

        List<WatchList> allwatchlists = watchlistDao.getWatchLists();
        watchlistDao.populateWatchlistData(allwatchlists);
        profile.defineWatchlists(allwatchlists);
    }

    private void populateDatapoints(UsersProfileVO profile) {


        profile.setDataPointPermissions(DAO.getInstance().getJdbcTemp().query(SELECT_DATA_POINT_PERMISSIONS,
                new Object[]{profile.getId()},
                new RowMapper<DataPointAccess>() {
                    public DataPointAccess mapRow(ResultSet rs, int rowNum)
                            throws SQLException {
                        DataPointAccess a = new DataPointAccess();
                        a.setDataPointId(rs.getInt(1));
                        a.setPermission(rs.getInt(2));
                        return a;
                    }
                }));
    }

    private void populateDataSources(UsersProfileVO profile) {
        profile.setDataSourcePermissions(DAO.getInstance().getJdbcTemp().queryForList(
                SELECT_DATA_SOURCE_PERMISSIONS,
                new Object[]{profile.getId()}, Integer.class));
    }

    private void populateViews(UsersProfileVO profile) {
        profile.setViewPermissions(DAO.getInstance().getJdbcTemp().query(SELECT_VIEW_PERMISSIONS,
                new Object[]{profile.getId()},
                new RowMapper<ViewAccess>() {
                    public ViewAccess mapRow(ResultSet rs, int rowNum)
                            throws SQLException {
                        ViewAccess a = new ViewAccess(rs.getInt(1), rs
                                .getInt(2));
                        return a;
                    }
                }));

        List<View> allviews = new ViewService().getViews();
        profile.defineViews(allviews);
    }

    private void populateUserProfilePermissions(List<UsersProfileVO> profiles) {
        for (UsersProfileVO profile : profiles) {
            LOG.debug("start");
            populateUserProfilePermissions(profile);
            LOG.debug("end");
        }
    }

    private void saveRelationalData(final UsersProfileVO usersProfile) {

        DAO.getInstance().getJdbcTemp().update("delete from dataSourceUsersProfiles where userProfileId=?",
                usersProfile.getId());
        DAO.getInstance().getJdbcTemp().update("delete from dataPointUsersProfiles where userProfileId=?",
                usersProfile.getId());
        DAO.getInstance().getJdbcTemp().update("delete from watchListUsersProfiles where userProfileId=?",
                usersProfile.getId());
        DAO.getInstance().getJdbcTemp().update("delete from viewUsersProfiles where userProfileId=?",
                usersProfile.getId());
        DAO.getInstance().getJdbcTemp().update("insert into dataSourceUsersProfiles (dataSourceId, userProfileId) values (?,?)",
                new BatchPreparedStatementSetter() {
                    public int getBatchSize() {
                        return usersProfile.getDataSourcePermissions().size();
                    }

                    public void setValues(PreparedStatement ps, int i)
                            throws SQLException {
                        ps.setInt(1, usersProfile.getDataSourcePermissions()
                                .get(i));
                        ps.setInt(2, usersProfile.getId());
                    }
                });
        DAO.getInstance().getJdbcTemp().update("insert into dataPointUsersProfiles (dataPointId, userProfileId, permission) values (?,?,?)",
                new BatchPreparedStatementSetter() {
                    public int getBatchSize() {
                        return usersProfile.getDataPointPermissions().size();
                    }

                    public void setValues(PreparedStatement ps, int i)
                            throws SQLException {
                        ps.setInt(1, usersProfile.getDataPointPermissions()
                                .get(i).getDataPointId());
                        ps.setInt(2, usersProfile.getId());
                        ps.setInt(3, usersProfile.getDataPointPermissions()
                                .get(i).getPermission());
                    }
                });
        DAO.getInstance().getJdbcTemp().update("insert into watchListUsersProfiles (watchlistId, userProfileId, permission) values (?,?,?)",
                new BatchPreparedStatementSetter() {
                    public int getBatchSize() {
                        return usersProfile.getWatchlistPermissions().size();
                    }

                    public void setValues(PreparedStatement ps, int i)
                            throws SQLException {
                        ps.setInt(1, usersProfile.getWatchlistPermissions()
                                .get(i).getId());
                        ps.setInt(2, usersProfile.getId());
                        ps.setInt(3, usersProfile.getWatchlistPermissions()
                                .get(i).getPermission());
                    }
                });
        DAO.getInstance().getJdbcTemp().update("insert into viewUsersProfiles (viewId, userProfileId, permission) values (?,?,?)",
                new BatchPreparedStatementSetter() {
                    public int getBatchSize() {
                        return usersProfile.getViewPermissions().size();
                    }

                    public void setValues(PreparedStatement ps, int i)
                            throws SQLException {
                        ps.setInt(1, usersProfile.getViewPermissions().get(i)
                                .getId());
                        ps.setInt(2, usersProfile.getId());
                        ps.setInt(3, usersProfile.getViewPermissions().get(i)
                                .getPermission());
                    }
                });

    }

    public UsersProfileVO getUserProfileByUserId(int userid) {


        UsersProfileVO profile = (UsersProfileVO) DAO.getInstance().getJdbcTemp().queryForObject(USERS_PROFILES_SELECT
                        + " where u.userId=?", new Object[]{userid},
                new RowMapper<UsersProfileVO>() {
                    public UsersProfileVO mapRow(ResultSet rs, int rowNum)
                            throws SQLException {
                        UsersProfileVO edt = new UsersProfileVO();
                        edt.setId(rs.getInt(1));
                        return edt;
                    }
                });

        if (profile != null) {
            profile = this.getUserProfileById(profile.getId());
        }

        populateUserProfilePermissions(profile);
        return profile;
    }

    public void grantUserAdminProfile(User user) {
        DAO.getInstance().getJdbcTemp().update("delete from usersUsersProfiles where userId=?",
                user.getId());

        // Add user to watchLists
        List<WatchList> watchLists = watchlistDao.getWatchLists();
        for (WatchList wl : watchLists) {
            watchlistDao.removeUserFromWatchList(wl.getId(), user.getId());
        }

        // Remove user from Views
        List<View> views = viewService.getViews();
        for (View view : views) {
            viewService.removeUserFromView(view.getId(), user.getId());
        }

        user.resetUserProfile();
    }

    public void resetUserProfile(User user) {

        DAO.getInstance().getJdbcTemp().update("delete from usersUsersProfiles where userId=?",
                user.getId());


        // Remove user from watchLists
        List<WatchList> watchLists = watchlistDao.getWatchLists();
        for (WatchList wl : watchLists) {
            watchlistDao.removeUserFromWatchList(wl.getId(), user.getId());
        }

        // Remove user from Views
        List<View> views = viewService.getViews();
        for (View view : views) {
            viewService.removeUserFromView(view.getId(), user.getId());
        }

        user.resetUserProfile();
    }

    public void setWatchlistDao(WatchListDao dao) {
        this.watchlistDao = dao;
    }

    public void setViewDao(ViewDao dao) {
        this.viewDao = dao;
    }

    public boolean userProfileExists(String xid) {
        UsersProfileVO profile = getUserProfileByXid(xid);
        return profile != null;
    }

    public void deleteUserProfile(final int usersProfileId) {
        // Get Users from Profile
        List<Integer> usersIds = DAO.getInstance().getJdbcTemp().queryForList(USERS_PROFILES_USERS_SELECT
                        + " where u.userProfileId=?", new Object[]{usersProfileId},
                Integer.class);

        // Reset user profile
        for (Integer userId : usersIds) {
            this.resetUserProfile(userService.getUser(userId));
        }

        getTransactionTemplate().execute(
                new TransactionCallbackWithoutResult() {
                    @SuppressWarnings("synthetic-access")
                    @Override
                    protected void doInTransactionWithoutResult(
                            TransactionStatus status) {
                        Object[] args = new Object[]{usersProfileId};

                        DAO.getInstance().getJdbcTemp().update("delete from usersProfiles where id=?", args);

                    }
                });
        currentProfileList.clear();
        currentProfileList = null;
    }

}
