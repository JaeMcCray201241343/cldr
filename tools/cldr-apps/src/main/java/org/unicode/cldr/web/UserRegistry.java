//  UserRegistry.java
//
//  Created by Steven R. Loomis on 14/10/2005.
//  Copyright 2005-2013 IBM. All rights reserved.
//

package org.unicode.cldr.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.json.bind.annotation.JsonbProperty;

import com.ibm.icu.dev.util.ElapsedTimer;
import com.ibm.icu.lang.UCharacter;

import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONString;
import org.unicode.cldr.test.CheckCLDR.Phase;
import org.unicode.cldr.util.CLDRConfig;
import org.unicode.cldr.util.CLDRConfig.Environment;
import org.unicode.cldr.util.CLDRInfo.UserInfo;
import org.unicode.cldr.util.CLDRLocale;
import org.unicode.cldr.util.LocaleNormalizer;
import org.unicode.cldr.util.LocaleSet;
import org.unicode.cldr.util.Organization;
import org.unicode.cldr.util.SpecialLocales;
import org.unicode.cldr.util.VoteResolver;
import org.unicode.cldr.util.VoteResolver.Level;
import org.unicode.cldr.util.VoteResolver.VoterInfo;
import org.unicode.cldr.web.api.UserItem;

/**
 * This class represents the list of all registered users. It contains an inner
 * class, UserRegistry.User, which represents an individual user.
 *
 * @see UserRegistry.User
 **/
public class UserRegistry {
    public static final String ADMIN_EMAIL = "admin@";

    /**
     * The number of anonymous users, ANONYMOUS_USER_COUNT, limits the number of distinct values that
     * can be added for a given locale and path as anonymous imported old losing votes. If eventually more
     * are needed, ANONYMOUS_USER_COUNT can be increased and more anonymous users will automatically
     * be created.
     */
    private static final int ANONYMOUS_USER_COUNT = 20;

    /**
     * Thrown to indicate the caller should log out.
     * @author srl
     */
    public class LogoutException extends Exception {
        private static final long serialVersionUID = 8960959307439428532L;
    }

    static Set<String> getCovGroupsForOrg(String st_org) {
        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement s = null;
        Set<String> res = new HashSet<>();

        try {
            conn = DBUtils.getInstance().getAConnection();
            s = DBUtils
                .prepareStatementWithArgs(
                    conn,
                    "select distinct cldr_interest.forum from cldr_interest where exists (select * from cldr_users  where cldr_users.id=cldr_interest.uid 	and cldr_users.org=?)",
                    st_org);
            rs = s.executeQuery();
            while (rs.next()) {
                res.add(rs.getString(1));
            }
            return res;
        } catch (SQLException se) {
            SurveyLog.logException(se, "Querying cov groups for org " + st_org, null);
            throw new InternalError("error: " + se.toString());
        } finally {
            DBUtils.close(rs, s, conn);
        }
    }

    static Set<CLDRLocale> anyVotesForOrg(String st_org) {
        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement s = null;
        Set<CLDRLocale> res = new HashSet<>();

        try {
            conn = DBUtils.getInstance().getAConnection();
            s = DBUtils
                .prepareStatementWithArgs(
                    conn,
                    "select distinct " + DBUtils.Table.VOTE_VALUE + ".locale from " + DBUtils.Table.VOTE_VALUE
                        + " where exists (select * from cldr_users	where " + DBUtils.Table.VOTE_VALUE + ".submitter=cldr_users.id and cldr_users.org=?)",
                    st_org);
            rs = s.executeQuery();
            while (rs.next()) {
                res.add(CLDRLocale.getInstance(rs.getString(1)));
            }
            return res;
        } catch (SQLException se) {
            SurveyLog.logException(se, "Querying voter locs for org " + st_org, null);
            throw new InternalError("error: " + se.toString());
        } finally {
            DBUtils.close(rs, s, conn);
        }
    }

    public interface UserChangedListener {
        public void handleUserChanged(User u);
    }

    private List<UserChangedListener> listeners = new LinkedList<>();

    public synchronized void addListener(UserChangedListener l) {
        listeners.add(l);
    }

    private synchronized void notify(User u) {
        for (UserChangedListener l : listeners) {
            l.handleUserChanged(u);
        }
    }

    private static final java.util.logging.Logger logger = SurveyLog.forClass(UserRegistry.class);
    // user levels

    /**
     * Administrator
     */
    public static final int ADMIN = VoteResolver.Level.admin.getSTLevel();

    /**
     * Technical Committee
     */
    public static final int TC = VoteResolver.Level.tc.getSTLevel();

    /**
     * Manager
     */
    public static final int MANAGER = VoteResolver.Level.manager.getSTLevel();

    /**
     * Regular Vetter
     */
    public static final int VETTER = VoteResolver.Level.vetter.getSTLevel();

    /**
     * "Street" = Guest Vetter
     */
    public static final int STREET = VoteResolver.Level.street.getSTLevel();

    /**
     * Locked user - can't login
     */
    public static final int LOCKED = VoteResolver.Level.locked.getSTLevel();

    /**
     * Anonymous user - special for imported old losing votes
     */
    public static final int ANONYMOUS = VoteResolver.Level.anonymous.getSTLevel();

    /**
     * min level
     */
    public static final int NO_LEVEL = -1;

    /**
     * special "IP" value referring to a user being added
     */
    public static final String FOR_ADDING = "(for adding)";

    private static final String INTERNAL = "INTERNAL";

    /**
     * List of all user levels - for UI presentation
     *
     * Code related to UserRegistry.EXPERT removed 2021-05-18 per CLDR-14597
     */
    public static final int ALL_LEVELS[] = { ADMIN, TC, MANAGER, VETTER, STREET, LOCKED };

    /**
     * get a level as a string - presentation form
     **/
    public static String levelToStr(int level) {
        return level + ": (" + levelAsStr(level) + ")";
    }

    /**
     * get just the raw level as a string
     */
    public static String levelAsStr(int level) {
        VoteResolver.Level l = VoteResolver.Level.fromSTLevel(level);
        if (l == null) {
            return "??";
        } else {
            return l.name().toUpperCase();
        }
    }

    /**
     * The name of the user sql database
     */
    public static final String CLDR_USERS = "cldr_users";
    public static final String CLDR_INTEREST = "cldr_interest";

    public static final String SQL_insertStmt = "INSERT INTO " + CLDR_USERS
        + "(userlevel,name,org,email,password,locales,lastlogin) " + "VALUES(?,?,?,?,?,?,NULL)";
    public static final String SQL_queryStmt_FRO = "SELECT id,name,userlevel,org,locales,intlocs,lastlogin from " + CLDR_USERS
        + " where email=? AND password=?";
    public static final String SQL_queryIdStmt_FRO = "SELECT name,org,email,userlevel,intlocs,locales,lastlogin,password from "
        + CLDR_USERS + " where id=?";
    public static final String SQL_queryEmailStmt_FRO = "SELECT id,name,userlevel,org,locales,intlocs,lastlogin,password from "
        + CLDR_USERS + " where email=?";
    public static final String SQL_touchStmt = "UPDATE " + CLDR_USERS + " set lastlogin=CURRENT_TIMESTAMP where id=?";
    public static final String SQL_removeIntLoc = "DELETE FROM " + CLDR_INTEREST + " WHERE uid=?";
    public static final String SQL_updateIntLoc = "INSERT INTO " + CLDR_INTEREST + " (uid,forum) VALUES(?,?)";

    private UserSettingsData userSettings;

    /**
     * This nested class is the representation of an individual user. It may not
     * have all fields filled out, if it is simply from the cache.
     */
    public class User implements Comparable<User>, UserInfo, JSONString {
        @Schema( description = "User ID")
        public int id; // id number
        @Schema( description = "numeric userlevel")
        public int userlevel = LOCKED; // user level
        @Schema( hidden = true )
        private String password; // password
        @Schema( description = "User email")
        public String email; //
        @Schema( description = "User org")
        public String org; // organization
        @Schema( description = "User name")
        public String name; // full name
        @Schema( name = "time", implementation = java.util.Date.class )
        public java.sql.Timestamp last_connect;
        @Schema( hidden = true )
        public String locales;
        @Schema( hidden = true )
        public String intlocs = null;
        @Schema( hidden = true )
        public String ip;

        @Schema( hidden = true )
        private String emailmd5 = null;

        public String getEmailHash() {
            if (emailmd5 == null) {
                String newHash = DigestUtils.md5Hex(email.trim().toLowerCase());
                emailmd5 = newHash;
                return newHash;
            }
            return emailmd5;
        }

        private UserSettings settings;

        private LocaleSet authorizedLocaleSet = null;
        private LocaleSet interestLocalesSet = null;

        /**
         * @deprecated may not use
         */
        @Deprecated
        private User() {
            this.id = UserRegistry.NO_USER;
            settings = userSettings.getSettings(id); // may not use settings.
        }

        public User(int id) {
            this.id = id;
            settings = userSettings.getSettings(id);
        }

        /**
         * Get a settings object for use with this user.
         *
         * @return
         */
        public UserSettings settings() {
            return settings;
        }

        public void touch() {
            UserRegistry.this.touch(id);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof User)) {
                return false;
            }
            User u = (User) other;
            return (u.id == id);
        }

        public void printPasswordLink(WebContext ctx) {
            UserRegistry.printPasswordLink(ctx, email, getPassword());
        }

        @Override
        public String toString() {
            return email + "(" + org + ")-" + levelAsStr(userlevel) + "#" + userlevel + " - " + name + ", locs=" + locales;
        }

        public String toHtml(User forUser) {
            if (forUser == null || !userIsTC(forUser)) {
                return "(" + org + "#" + id + ")";
            } else {
                return "<a href='mailto:" + email + "'>" + name + "</a>-" + levelAsStr(userlevel).toLowerCase();
            }
        }

        public String toHtml() {
            return "<a href='mailto:" + email + "'>" + name + "</a>-" + levelAsStr(userlevel).toLowerCase();
        }

        public String toString(User forUser) {
            if (forUser == null || !userIsTC(forUser)) {
                return "(" + org + "#" + id + ")";
            } else {
                return email + "(" + org + ")-" + levelAsStr(userlevel) + "#" + userlevel + " - " + name;
            }
        }

        @Override
        public int hashCode() {
            return id;
        }

        /**
         * is the user interested in this locale?
         */
        public boolean interestedIn(CLDRLocale locale) {
            return getInterestLocales().contains(locale);
        }

        /**
         * Set of interest locales for this user
         *
         * @return null or LocaleNormalizer.ALL_LOCALES_SET for 'all', otherwise a set of CLDRLocales
         */
        public LocaleSet getInterestLocales() {
            if (interestLocalesSet == null) {
                if (userIsManagerOrStronger(this)) {
                    interestLocalesSet = LocaleNormalizer.setFromStringQuietly(intlocs, null);
                } else {
                    interestLocalesSet = LocaleNormalizer.setFromStringQuietly(locales, null);
                }
            }
            return interestLocalesSet;
        }

        /**
         * Convert this User to a VoteResolver.VoterInfo. Not cached.
         */
        private VoterInfo createVoterInfo() {
            Organization o = this.getOrganization();
            VoteResolver.Level l = this.getLevel();
            VoterInfo v = new VoterInfo(o, l,
                // do not allow VoteResolver.VoterInfo to see the actual "name", because it is sent to the client.
                "#"+Integer.toString(id),
                getAuthorizedLocaleSet());
            return v;
        }

        private LocaleSet getAuthorizedLocaleSet() {
            if (authorizedLocaleSet == null) {
                LocaleSet orgLocales = getOrganization().getCoveredLocales();
                authorizedLocaleSet = LocaleNormalizer.setFromStringQuietly(locales, orgLocales);
            }
            return authorizedLocaleSet;
        }

        @Override
        public VoterInfo getVoterInfo() {
            return getVoterToInfo(id);
        }

        @Schema( name = "userLevelName", description = "VoteREsolver.Level user level" )
        public synchronized VoteResolver.Level getLevel() {
            return VoteResolver.Level.fromSTLevel(this.userlevel);
        }

        String getPassword() {
            return password;
        }

        /** here to allow one JSP to get at the password, but otherwise keep the field hidden */
        @Deprecated
        public String internalGetPassword() {
            return getPassword();
        }

        public synchronized Organization getOrganization() {
            if (vr_org == null) {
                vr_org = UserRegistry.computeVROrganization(this.org);
            }
            return vr_org;
        }

        private Organization vr_org = null;

        private String voterOrg = null;

        /**
         * Convenience function for returning the "VoteResult friendly"
         * organization.
         */
        public String voterOrg() {
            if (voterOrg == null) {
                voterOrg = getVoterInfo().getOrganization().name();
            }
            return voterOrg;
        }

        /**
         * Is this user an administrator 'over' the given other user?
         *
         * @param other
         * @deprecated
         *
         * Maybe this should NOT be deprecated, since it's shorter and more convenient
         * than VoteResolver.Level.isManagerFor()
         */
        @Deprecated
        public boolean isAdminFor(User other) {
            return getLevel().isManagerFor(getOrganization(), other.getLevel(), other.getOrganization());
        }

        public boolean isSameOrg(User other) {
            return getOrganization() == other.getOrganization();
        }

        /**
         * Is this user an administrator 'over' this user? Always true if admin,
         * or if TC in same org.
         *
         * @param org
         */
        public boolean isAdminForOrg(String org) {
            boolean adminOrRelevantTc = UserRegistry.userIsAdmin(this) ||

                ((UserRegistry.userIsTC(this) || this.userlevel == MANAGER) && (org != null) && this.org.equals(org));
            return adminOrRelevantTc;
        }

        @Override
        public int compareTo(User other) {
            if (other == this || other.equals(this))
                return 0;
            if (this.id < other.id) {
                return -1;
            } else {
                return 1;
            }
        }

        public Organization vrOrg() {
            return Organization.fromString(voterOrg());
        }

        /**
         * Doesn't send the password, does send other info
         * TODO: remove this in favor of jax-rs serialization
         */
        @Override
        public String toJSONString() throws JSONException {
            return new JSONObject().put("email", email)
                .put("emailHash", getEmailHash())
                .put("name", name)
                .put("userlevel", userlevel)
                .put("votecount", getLevel().getVotes())
                .put("voteCountMenu", getLevel().getVoteCountMenu())
                .put("userlevelName", UserRegistry.levelAsStr(userlevel))
                .put("org", vrOrg().name())
                .put("orgName", vrOrg().displayName)
                .put("id", id)
                .toString();
        }

        public boolean canImportOldVotes() {
            return UserRegistry.userIsVetter(this) && (CLDRConfig.getInstance().getPhase() == Phase.SUBMISSION);
        }

        @Schema( description="how much this user’s vote counts for")
        // @JsonbProperty("votecount")
        public int getVoteCount() {
            return getLevel().getVotes();
        }

        @Schema( description="how much this user’s vote counts for")
        // @JsonbProperty("voteCountMenu")
        public Integer[] getVoteCountMenu() {
            return getLevel().getVoteCountMenu().toArray(new Integer[0]);
        }

        /**
         * This one is hidden because it uses JSONObject and can't be serialized
         */
        JSONObject getPermissionsJson() throws JSONException {
            return new JSONObject()
                .put("userCanImportOldVotes", canImportOldVotes())
                .put("userCanUseVettingSummary", userCanUseVettingSummary(this))
                .put("userCanCreateSummarySnapshot", userCanCreateSummarySnapshot(this))
                .put("userCanMonitorForum", userCanMonitorForum(this))
                .put("userIsAdmin", userIsAdmin(this))
                .put("userIsManager", getLevel().canManageSomeUsers())
                .put("userIsTC", userIsTC(this))
                .put("userIsVetter", userIsVetter(this) && !userIsTC(this))
                .put("userIsLocked", userIsLocked(this));
        }

        /**
         * this property is called permissionsJson for compatiblity
         * @return
         */
        @JsonbProperty( "permissionsJson" )
        @Schema( description = "array of permissions for this user" )
        public Map<String, Boolean> getPermissions() {
            Map<String, Boolean> m = new HashMap<>();

            m.put("userCanImportOldVotes", canImportOldVotes());
            m.put("userCanUseVettingSummary", userCanUseVettingSummary(this));
            m.put("userCanCreateSummarySnapshot", userCanCreateSummarySnapshot(this));
            m.put("userCanMonitorForum", userCanMonitorForum(this));
            m.put("userIsAdmin", userIsAdmin(this));
            m.put("userIsTC", userIsTC(this));
            m.put("userIsManager", getLevel().canManageSomeUsers());
            m.put("userIsVetter", userIsVetter(this) && !userIsTC(this));
            m.put("userIsLocked", userIsLocked(this));

            return m;
        }

        public void setPassword(String randomPass) {
            this.password = randomPass;
        }

        /**
         * Does this user have permission to modify the given locale?
         *
         * @param locale the CLDRLocale
         * @return true or false
         *
         * Code related to userIsExpert, UserRegistry.EXPERT removed 2021-05-18 per CLDR-14597
         */
        private boolean hasLocalePermission(CLDRLocale locale) {
            /*
             * the 'und' locale and sublocales can always be modified
             */
            if (SpecialLocales.getType(locale) == SpecialLocales.Type.scratch) {
                return true;
            }
            /*
             * Per CLDR-14597, TC and ADMIN can vote in any locale;
             * MANAGER can vote if their organization covers the locale
             */
            if (userlevel <= UserRegistry.TC) {
                return true;
            }
            if (userlevel == UserRegistry.MANAGER) {
                return getOrganization().getCoveredLocales().containsLocaleOrParent(locale);
            }
            return getAuthorizedLocaleSet().contains(locale);
        }

        /**
         * Get a locale suitable for example paths to be shown to this user,
         * when "USER" is used as a "wildcard" for the locale name in a URL
         *
         * @return the CLDRLocale, or null if no particularly suitable locale found
         */
        public CLDRLocale exampleLocale() {
            final LocaleSet localeSet = getAuthorizedLocaleSet();
            if (!localeSet.isEmpty() && !localeSet.isAllLocales()) {
                return localeSet.firstElement();
            }
            return null;
        }

        public UserItem toUserItem() {
            UserItem u = new UserItem();
            u.id = this.id;
            u.level = getLevel().name();
            u.name = this.name;
            u.org = this.org;
            u.email = this.email;
            u.assignedLocales = this.getAuthorizedLocaleSet().toStringArray();
            if (this.last_connect != null) {
                u.lastLogin = this.last_connect.toInstant();
            } else {
                u.lastLogin = null;
            }
            return u;
        }
    }

    public static void printPasswordLink(WebContext ctx, String email, String password) {
        ctx.println("<a href='" + ctx.base() + "?email=" + email + "&amp;pw=" + password + "'>Login for " + email + "</a>");
    }

    private static Map<String, Organization> orgToVrOrg = new HashMap<>();

    public static synchronized Organization computeVROrganization(String org) {
        Organization o = Organization.fromString(org);
        if (o == null) {
            o = orgToVrOrg.get(org);
        } else {
            orgToVrOrg.put(org, o); // from Organization.fromString
        }
        if (o == null) {
            try {
                /*
                 * TODO: "utilika" always logs WARNING: ** Unknown organization (treating as Guest): Utilika Foundation"
                 * Map to "The Long Now Foundation" instead? Cf. https://unicode.org/cldr/trac/ticket/6320
                 * Organization.java has: longnow("The Long Now Foundation", "Long Now", "PanLex")
                 */
                String arg = org.replaceAll("Utilika Foundation", "utilika")
                    .replaceAll("Government of Pakistan - National Language Authority", "pakistan")
                    .replaceAll("ICT Agency of Sri Lanka", "srilanka").toLowerCase().replaceAll("[.-]", "_");
                o = Organization.valueOf(arg);
            } catch (IllegalArgumentException iae) {
                o = Organization.guest;
                SurveyLog.warnOnce(logger, "** Unknown organization (treating as Guest): " + org);
            }
            orgToVrOrg.put(org, o);
        }
        return o;
    }

    /**
     * Called by SM to create the reg
     */
    public static UserRegistry createRegistry(SurveyMain theSm) throws SQLException {
        sm = theSm;
        UserRegistry reg = new UserRegistry();
        reg.setupDB();
        return reg;
    }

    /**
     * internal - called to setup db
     */
    private void setupDB() throws SQLException {
        // must be set up first.
        userSettings = UserSettingsData.getInstance(sm);

        String sql = null;
        Connection conn = DBUtils.getInstance().getDBConnection();
        try {
            synchronized (conn) {
                boolean hadUserTable = DBUtils.hasTable(conn, CLDR_USERS);
                if (!hadUserTable) {
                    sql = createUserTable(conn);
                    conn.commit();
                } else if (!DBUtils.db_Derby) {
                    /* update table to DATETIME instead of TIMESTAMP */
                    Statement s = conn.createStatement();
                    sql = "alter table cldr_users change lastlogin lastlogin DATETIME";
                    s.execute(sql);
                    s.close();
                    conn.commit();
                }

                //create review and post table
                sql = "(see ReviewHide.java)";
                ReviewHide.createTable(conn);
                boolean hadInterestTable = DBUtils.hasTable(conn, CLDR_INTEREST);
                if (!hadInterestTable) {
                    Statement s = conn.createStatement();

                    sql = ("create table " + CLDR_INTEREST + " (uid INT NOT NULL , " + "forum  varchar(256) not null " + ")");
                    s.execute(sql);
                    sql = "CREATE  INDEX " + CLDR_INTEREST + "_id_loc ON " + CLDR_INTEREST + " (uid) ";
                    s.execute(sql);
                    sql = "CREATE  INDEX " + CLDR_INTEREST + "_id_for ON " + CLDR_INTEREST + " (forum) ";
                    s.execute(sql);
                    SurveyLog.debug("DB: created " + CLDR_INTEREST);
                    sql = null;
                    s.close();
                    conn.commit();
                }

                myinit(); // initialize the prepared statements

                if (!hadInterestTable) {
                    setupIntLocs(); // set up user -> interest table mapping
                }

            }
        } catch (SQLException se) {
            se.printStackTrace();
            System.err.println("SQL err: " + DBUtils.unchainSqlException(se));
            System.err.println("Last SQL run: " + sql);
            throw se;
        } finally {
            DBUtils.close(conn);
        }
    }

    /**
     * @param conn
     * @return
     * @throws SQLException
     */
    private String createUserTable(Connection conn) throws SQLException {
        String sql;
        Statement s = conn.createStatement();

        sql = ("create table " + CLDR_USERS + "(id INT NOT NULL " + DBUtils.DB_SQL_IDENTITY + ", " + "userlevel int not null, "
            + "name " + DBUtils.DB_SQL_UNICODE + " not null, " + "email varchar(128) not null UNIQUE, "
            + "org varchar(256) not null, " + "password varchar(100) not null, " + "audit varchar(1024) , "
            + "locales varchar(1024) , " +
            // "prefs varchar(1024) , " + /* deprecated Dec 2010. Not used
            // anywhere */
            "intlocs varchar(1024) , " + // added apr 2006: ALTER table
            // CLDR_USERS ADD COLUMN intlocs
            // VARCHAR(1024)
            "lastlogin " + DBUtils.DB_SQL_TIMESTAMP0 + // added may 2006:
            // alter table
            // CLDR_USERS ADD
            // COLUMN lastlogin
            // TIMESTAMP
            (!DBUtils.db_Mysql ? ",primary key(id)" : "") + ")");
        s.execute(sql);
        sql = ("INSERT INTO " + CLDR_USERS + "(userlevel,name,org,email,password) " + "VALUES(" + ADMIN + "," + "'admin',"
            + "'SurveyTool'," + "'" + ADMIN_EMAIL + "'," + "'" + SurveyMain.vap + "')");
        s.execute(sql);
        sql = null;
        SurveyLog.debug("DB: added user Admin");

        s.close();
        return sql;
    }

    /**
     * ID# of the user
     */
    static final int ADMIN_ID = 1;

    /**
     * special ID meaning 'all'
     */
    static final int ALL_ID = -1;

    private void myinit() throws SQLException {
    }

    /**
     * info = name/email/org immutable info, keep it in a separate list for
     * quick lookup.
     */
    public final static int CHUNKSIZE = 128;
    int arraySize = 0;
    UserRegistry.User infoArray[] = new UserRegistry.User[arraySize];

    /**
     * Mark user as modified
     *
     * @param id
     */
    public void userModified(int id) {
        synchronized (infoArray) {
            try {
                infoArray[id] = null;
            } catch (IndexOutOfBoundsException ioob) {
                // nothing to do
            }
        }
        userModified(); // do this if any users are modified
    }

    /**
     * Mark the UserRegistry as changed, purging the VoterInfo map
     *
     * @see #getVoterToInfo()
     */
    private void userModified() {
        voterInfo = null;
    }

    /**
     * Get the singleton user for this ID.
     *
     * @param id
     * @return singleton, or null if not found/invalid
     */
    public UserRegistry.User getInfo(int id) {
        if (id < 0) {
            return null;
        }
        synchronized (infoArray) {
            User ret = null;
            try {
                ret = infoArray[id];
            } catch (IndexOutOfBoundsException ioob) {
                ret = null; // not found
            }

            if (ret == null) { // synchronized(conn) {
                ResultSet rs = null;
                PreparedStatement pstmt = null;
                Connection conn = DBUtils.getInstance().getAConnection();
                try {
                    pstmt = DBUtils.prepareForwardReadOnly(conn, UserRegistry.SQL_queryIdStmt_FRO);
                    pstmt.setInt(1, id);
                    // First, try to query it back from the DB.
                    rs = pstmt.executeQuery();
                    if (!rs.next()) {
                        return null;
                    }
                    User u = new UserRegistry.User(id);
                    // from params:
                    u.name = DBUtils.getStringUTF8(rs, 1);
                    u.org = rs.getString(2);
                    u.getOrganization(); // verify

                    u.email = rs.getString(3);
                    u.userlevel = rs.getInt(4);
                    u.intlocs = rs.getString(5);
                    u.locales = LocaleNormalizer.normalizeQuietly(rs.getString(6));
                    u.last_connect = rs.getTimestamp(7);
                    u.password = rs.getString(8);
                    ret = u; // let it finish..

                    if (id >= arraySize) {
                        int newchunk = (((id + 1) / CHUNKSIZE) + 1) * CHUNKSIZE;
                        infoArray = new UserRegistry.User[newchunk];
                        arraySize = newchunk;
                    }
                    infoArray[id] = u;
                    // good so far..
                    if (rs.next()) {
                        // dup returned!
                        throw new InternalError("Dup user id # " + id);
                    }
                } catch (SQLException se) {
                    logger.log(java.util.logging.Level.SEVERE,
                        "UserRegistry: SQL error trying to get #" + id + " - " + DBUtils.unchainSqlException(se), se);
                    throw new InternalError("UserRegistry: SQL error trying to get #" + id + " - "
                        + DBUtils.unchainSqlException(se));
                } catch (Throwable t) {
                    logger.log(java.util.logging.Level.SEVERE, "UserRegistry: some error trying to get #" + id, t);
                    throw new InternalError("UserRegistry: some error trying to get #" + id + " - " + t.toString());
                } finally {
                    // close out the RS
                    DBUtils.close(rs, pstmt, conn);
                } // end try
            }
            return ret;
        } // end synch array
    }

    private final String normalizeEmail(String str) {
        return str.trim().toLowerCase();
    }

    public final UserRegistry.User get(String pass, String email, String ip) throws LogoutException {
        boolean letmein = false;
        if (ADMIN_EMAIL.equals(email) && SurveyMain.vap.equals(pass)) {
            letmein = true;
        }
        return get(pass, email, ip, letmein);
    }

    public void touch(int id) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = DBUtils.getInstance().getDBConnection();
            pstmt = conn.prepareStatement(SQL_touchStmt);
            pstmt.setInt(1, id);
            pstmt.executeUpdate();
            conn.commit();
        } catch (SQLException se) {
            logger.log(java.util.logging.Level.SEVERE,
                "UserRegistry: SQL error trying to touch " + id + " - " + DBUtils.unchainSqlException(se), se);
            throw new InternalError("UserRegistry: SQL error trying to touch " + id + " - " + DBUtils.unchainSqlException(se));
        } finally {
            DBUtils.close(pstmt, conn);
        }
    }

    /**
     * @param letmein
     *            The VAP was given - allow the user in regardless
     * @param pass
     *            the password to match. If NULL, means just do a lookup
     */
    public UserRegistry.User get(String pass, String email, String ip, boolean letmein) throws LogoutException {
        if ((email == null) || (email.length() <= 0)) {
            return null; // nothing to do
        }
        if (((pass != null && pass.length() <= 0)) && !letmein) {
            return null; // nothing to do
        }

        email = normalizeEmail(email);

        ResultSet rs = null;
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = DBUtils.getInstance().getAConnection();
            if ((pass != null) && !letmein) {
                pstmt = DBUtils.prepareForwardReadOnly(conn, SQL_queryStmt_FRO);
                pstmt.setString(1, email);
                pstmt.setString(2, pass);
            } else {
                pstmt = DBUtils.prepareForwardReadOnly(conn, SQL_queryEmailStmt_FRO);
                pstmt.setString(1, email);
            }
            // First, try to query it back from the DB.
            rs = pstmt.executeQuery();
            if (!rs.next()) { // user was not found.
                throw new UserRegistry.LogoutException();
            }
            User u = new UserRegistry.User(rs.getInt(1));

            // from params:
            u.password = pass;
            if (letmein) {
                u.password = rs.getString(8);
            }
            u.email = normalizeEmail(email);
            // from db: (id,name,userlevel,org,locales)
            u.name = DBUtils.getStringUTF8(rs, 2);// rs.getString(2);
            u.userlevel = rs.getInt(3);
            u.org = rs.getString(4);
            u.locales = rs.getString(5);
            u.intlocs = rs.getString(6);
            u.last_connect = rs.getTimestamp(7);

            // good so far..

            if (rs.next()) {
                // dup returned!
                logger.severe("Duplicate user for " + email + " - ids " + u.id + " and " + rs.getInt(1));
                return null;
            }
            return u;
        } catch (SQLException se) {
            logger.log(java.util.logging.Level.SEVERE,
                "UserRegistry: SQL error trying to get " + email + " - " + DBUtils.unchainSqlException(se), se);
            throw new InternalError("UserRegistry: SQL error trying to get " + email + " - " + DBUtils.unchainSqlException(se));
        } catch (LogoutException le) {
            if (pass != null) {
                // only log this if they were actually trying to login.
                logger.log(java.util.logging.Level.SEVERE, "AUTHENTICATION FAILURE; email=" + email + "; ip=" + ip);
            }
            throw le; // bubble
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.SEVERE, "UserRegistry: some error trying to get " + email, t);
            throw new InternalError("UserRegistry: some error trying to get " + email + " - " + t.toString());
        } finally {
            // close out the RS
            DBUtils.close(rs, pstmt, conn);
        } // end try
    } // end get

    public UserRegistry.User get(String email) {
        try {
            return get(null, email, INTERNAL);
        } catch (LogoutException le) {
            return null;
        }
    }

    /**
     * @deprecated
     * @return
     */
    @Deprecated
    public UserRegistry.User getEmptyUser() {
        User u = new User();
        u.name = "UNKNOWN";
        u.email = "UN@KNOWN.example.com";
        u.org = "NONE";
        u.password = null;
        u.locales = "";
        return u;
    }

    static SurveyMain sm = null; // static for static checking of defaultContent

    /**
     * Public for tests
     */
    public UserRegistry() {
    }

    /**
     * Get a list of user ids for a particular org, or all.
     * Anonymous users are always skipped.
     * @param org org to filter on, or null for all
     * @param includeLocked if true, include LOCKED users, otherwise they are omitted
     * @return
     * @throws SQLException
     */
    public List<Integer> getUserIdList(String org, boolean includeLocked) throws SQLException {
        // lifted from UserList.java
        Connection conn = null;
        PreparedStatement ps = null;
        java.sql.ResultSet rs = null;
        // List<UserList> users = new LinkedList<>();
        // collect list of ids
        List<Integer> ids = new LinkedList<>();
        conn = DBUtils.getInstance().getAConnection();
        ps = CookieSession.sm.reg.list(org, conn); // org = null to list all
        rs = ps.executeQuery();
        while (rs.next()) {
            int level = rs.getInt(2);
            if (level == UserRegistry.ANONYMOUS) {
                continue;
            }
            if (!includeLocked
                && level >= UserRegistry.LOCKED) {
                continue;
            }
            int id = rs.getInt(1);
            ids.add(id);
        }
        return ids;
    }

    // ------- special things for "list" mode:

    public java.sql.PreparedStatement list(String organization, Connection conn) throws SQLException {
        if (organization == null) {
            return DBUtils.prepareStatementForwardReadOnly(conn, "listAllUsers", "SELECT id,userlevel,name,email,org,locales,intlocs,lastlogin FROM " + CLDR_USERS + " ORDER BY org,userlevel,name ");
        } else {
            PreparedStatement ps = DBUtils.prepareStatementWithArgsFRO(conn,
                    "SELECT id,userlevel,name,email,org,locales,intlocs,lastlogin FROM " + CLDR_USERS + " WHERE org=? ORDER BY org,userlevel,name");
            ps.setString(1,  organization);
            return ps;
        }
    }

    public java.sql.ResultSet listPass(Connection conn) throws SQLException {
        ResultSet rs = null;
        Statement s = null;
        final String ORDER = " ORDER BY id ";
        s = conn.createStatement();
        rs = s.executeQuery("SELECT id,userlevel,name,email,org,locales,intlocs, password FROM " + CLDR_USERS + ORDER);
        return rs;
    }

    private void setupIntLocs() throws SQLException {
        Connection conn = DBUtils.getInstance().getDBConnection();
        PreparedStatement removeIntLoc = null;
        PreparedStatement updateIntLoc = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            removeIntLoc = conn.prepareStatement(SQL_removeIntLoc);
            updateIntLoc = conn.prepareStatement(SQL_updateIntLoc);
            ps = list(null, conn);
            rs = ps.executeQuery();
            ElapsedTimer et = new ElapsedTimer();
            int count = 0;
            while (rs.next()) {
                int user = rs.getInt(1);
                updateIntLocs(user, false, conn, removeIntLoc, updateIntLoc);
                count++;
            }
            conn.commit();
            SurveyLog.debug("update:" + count + " user's locales updated " + et);
        } finally {
            DBUtils.close(removeIntLoc, updateIntLoc, conn, ps, rs);
        }
    }

    /**
     * assumes caller has a lock on conn
     */
    private String updateIntLocs(int user, Connection conn) throws SQLException {
        PreparedStatement removeIntLoc = null;
        PreparedStatement updateIntLoc = null;
        try {
            removeIntLoc = conn.prepareStatement(SQL_removeIntLoc);
            updateIntLoc = conn.prepareStatement(SQL_updateIntLoc);
            return updateIntLocs(user, true, conn, removeIntLoc, updateIntLoc);
        } finally {
            DBUtils.close(removeIntLoc, updateIntLoc);
        }
    }

    /**
     * assumes caller has a lock on conn
     */
    private String updateIntLocs(int id, boolean doCommit, Connection conn, PreparedStatement removeIntLoc, PreparedStatement updateIntLoc)
        throws SQLException {

        User user = getInfo(id);
        if (user == null) {
            return "";
        }
        logger.finer("uil: remove for user " + id);
        removeIntLoc.setInt(1, id);
        removeIntLoc.executeUpdate();

        LocaleSet intLocSet = user.getInterestLocales();
        logger.finer("uil: intlocs " + id + " = " + intLocSet.toString());
        if (intLocSet != null && !intLocSet.isAllLocales() && !intLocSet.isEmpty()) {
            /*
             * Simplify locales. For example simplify "pt_PT" to "pt" with loc.getLanguage().
             * Avoid adding duplicates to the table. For example, if the same user has both
             * "pt" and "pt_PT", only add one row, for "pt".
             */
            Set<String> languageSet = new TreeSet<>();
            for (CLDRLocale loc : intLocSet.getSet()) {
                languageSet.add(loc.getLanguage());
            }
            for (String lang : languageSet) {
                logger.finer("uil: intlocs " + id + " + " + lang);
                updateIntLoc.setInt(1, id);
                updateIntLoc.setString(2, lang);
                updateIntLoc.executeUpdate();
            }
        }

        if (doCommit) {
            conn.commit();
        }
        return "";
    }

    String setUserLevel(User me, User them, int newLevel) {
        if (!canSetUserLevel(me, them, newLevel)) {
            return ("[Permission Denied]");
        }
        String orgConstraint = null;
        String msg = "";
        if (me.userlevel == ADMIN) {
            orgConstraint = ""; // no constraint
        } else {
            orgConstraint = " AND org='" + me.org + "' ";
        }
        Connection conn = null;
        try {
            conn = DBUtils.getInstance().getDBConnection();
            Statement s = conn.createStatement();
            String theSql = "UPDATE " + CLDR_USERS + " SET userlevel=" + newLevel + " WHERE id=" + them.id + " AND email='"
                + them.email + "' " + orgConstraint;
            logger.info("Attempt user update by " + me.email + ": " + theSql);
            int n = s.executeUpdate(theSql);
            conn.commit();
            userModified(them.id);
            if (n == 0) {
                msg = msg + " [Error: no users were updated!] ";
                logger.severe("Error: 0 records updated.");
            } else if (n != 1) {
                msg = msg + " [Error in updating users!] ";
                logger.severe("Error: " + n + " records updated!");
            } else {
                msg = msg + " [user level set]";
                msg = msg + updateIntLocs(them.id, conn);
            }
        } catch (SQLException se) {
            msg = msg + " exception: " + DBUtils.unchainSqlException(se);
        } catch (Throwable t) {
            msg = msg + " exception: " + t.toString();
        } finally {
            DBUtils.closeDBConnection(conn);
        }

        return msg;
    }

    public boolean canSetUserLevel(User me, User them, int newLevel) {
        final VoteResolver.Level myLevel = VoteResolver.Level.fromSTLevel(me.userlevel);
        if (!myLevel.canCreateOrSetLevelTo(VoteResolver.Level.fromSTLevel(newLevel))) {
            return false;
        }
        // Can't change your own level
        if (me.id == them.id) {
            return false;
        }
        // Can't change the level of anyone whose current level is more privileged than yours
        if (them.userlevel < me.userlevel) {
            return false;
        }
        // Can't change the level of someone in a different org, unless you are admin
        if (myLevel != Level.admin && !me.isSameOrg(them)) {
            return false;
        }
        return true;
    }

    /**
     * Set the authorized locales, or the interest locales, for the specified user
     *
     * @param session the CookieSession
     * @param user the specified user (sometimes distinct from session.user)
     * @param newLocales the set of locales, as a string like "am fr zh"
     * @param intLocs true to set interest locales, false to set authorized locales
     * @return a message string describing the result
     */
    public String setLocales(CookieSession session, User user, String newLocales, boolean intLocs) {
        int theirId = user.id;
        String theirEmail = user.email;

        // make sure other user is at or below userlevel
        if (!intLocs && !session.user.isAdminFor(getInfo(theirId))) {
            return ("[Permission Denied]");
        }
        String msg = "";
        if (!intLocs) {
            final LocaleNormalizer locNorm = new LocaleNormalizer();
            newLocales = locNorm.normalizeForSubset(newLocales, user.getOrganization().getCoveredLocales());
            if (locNorm.hasMessage()) {
                msg = locNorm.getMessageHtml() + "<br />";
            }
        } else {
            newLocales = LocaleNormalizer.normalizeQuietly(newLocales);
        }
        String orgConstraint = null;
        if (session.user.userlevel == ADMIN) {
            orgConstraint = ""; // no constraint
        } else {
            orgConstraint = " AND org='" + session.user.org + "' ";
        }
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            final String normalizedLocales = newLocales;
            conn = DBUtils.getInstance().getDBConnection();
            String theSql = "UPDATE " + CLDR_USERS + " SET " + (intLocs ? "intlocs" : "locales") + "=? WHERE id=" + theirId
                + " AND email='" + theirEmail + "' " + orgConstraint;
            ps = conn.prepareStatement(theSql);
            logger.info("Attempt user locales update by " + session.user.email + ": " + theSql + " - " + newLocales);
            ps.setString(1, normalizedLocales);
            int n = ps.executeUpdate();
            conn.commit();
            userModified(theirId);
            if (n == 0) {
                msg += " [Error: no users were updated!] ";
                logger.severe("Error: 0 records updated.");
            } else if (n != 1) {
                msg += " [Error in updating users!] ";
                logger.severe("Error: " + n + " records updated!");
            } else {
                msg += " [locales set]" + updateIntLocs(theirId, conn);
            }
        } catch (SQLException se) {
            msg = msg + " exception: " + DBUtils.unchainSqlException(se);
        } catch (Throwable t) {
            msg = msg + " exception: " + t.toString();
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException se) {
                logger.log(java.util.logging.Level.SEVERE,
                    "UserRegistry: SQL error trying to close. " + DBUtils.unchainSqlException(se), se);
            }
        }
        return msg;
    }

    String delete(WebContext ctx, int theirId, String theirEmail) {
        if (!ctx.session.user.isAdminFor(getInfo(theirId))) {
            return ("[Permission Denied]");
        }

        String orgConstraint = null; // keep org constraint in place
        String msg = "";
        if (ctx.session.user.userlevel == ADMIN) {
            orgConstraint = ""; // no constraint
        } else {
            orgConstraint = " AND org='" + ctx.session.user.org + "' ";
        }
        Connection conn = null;
        Statement s = null;
        try {
            conn = DBUtils.getInstance().getDBConnection();
            s = conn.createStatement();
            String theSql = "DELETE FROM " + CLDR_USERS + " WHERE id=" + theirId + " AND email='" + theirEmail + "' "
                + orgConstraint;
            logger.info("Attempt user DELETE by " + ctx.session.user.email + ": " + theSql);
            int n = s.executeUpdate(theSql);
            conn.commit();
            userModified(theirId);
            if (n == 0) {
                msg = msg + " [Error: no users were removed!] ";
                logger.severe("Error: 0 users removed.");
            } else if (n != 1) {
                msg = msg + " [Error in removing users!] ";
                logger.severe("Error: " + n + " records removed!");
            } else {
                msg = msg + " [removed OK]";
            }
        } catch (SQLException se) {
            msg = msg + " exception: " + DBUtils.unchainSqlException(se);
        } catch (Throwable t) {
            msg = msg + " exception: " + t.toString();
        } finally {
            DBUtils.close(s, conn);
        }
        return msg;
    }

    public enum InfoType {
        INFO_EMAIL("E-mail", "email"), INFO_NAME("Name", "name"), INFO_PASSWORD("Password", "password"), INFO_ORG("Organization",
            "org");
        private static final String CHANGE = "change_";
        private String sqlField;
        private String title;

        InfoType(String title, String sqlField) {
            this.title = title;
            this.sqlField = sqlField;
        }

        @Override
        public String toString() {
            return title;
        }

        public String field() {
            return sqlField;
        }

        public static InfoType fromAction(String action) {
            if (action != null && action.startsWith(CHANGE)) {
                String which = action.substring(CHANGE.length());
                return InfoType.valueOf(which);
            } else {
                return null;
            }
        }

        public String toAction() {
            return CHANGE + name();
        }
    }

    String updateInfo(WebContext ctx, int theirId, String theirEmail, InfoType type, String value) {
        if (type == InfoType.INFO_ORG && ctx.session.user.userlevel > ADMIN) {
            return ("[Permission Denied]");
        }

        if (type == InfoType.INFO_EMAIL) {
            value = normalizeEmail(value);
        } else {
            value = value.trim();
        }

        if (!ctx.session.user.isAdminFor(getInfo(theirId))) {
            return ("[Permission Denied]");
        }

        String msg = "";
        Connection conn = null;
        PreparedStatement updateInfoStmt = null;
        try {
            conn = DBUtils.getInstance().getDBConnection();

            updateInfoStmt = conn.prepareStatement("UPDATE " + CLDR_USERS + " set " + type.field() + "=? WHERE id=? AND email=?");
            if (type == UserRegistry.InfoType.INFO_NAME) { // unicode treatment
                DBUtils.setStringUTF8(updateInfoStmt, 1, value);
            } else {
                updateInfoStmt.setString(1, value);
            }
            updateInfoStmt.setInt(2, theirId);
            updateInfoStmt.setString(3, theirEmail);

            logger.info("Attempt user UPDATE by " + ctx.session.user.email + ": " + type.toString() + " = "
                + ((type != InfoType.INFO_PASSWORD) ? value : "********"));
            int n = updateInfoStmt.executeUpdate();
            conn.commit();
            userModified(theirId);
            if (n == 0) {
                msg = msg + " [Error: no users were updated!] ";
                logger.severe("Error: 0 users updated.");
            } else if (n != 1) {
                msg = msg + " [Error in updated users!] ";
                logger.severe("Error: " + n + " updated removed!");
            } else {
                msg = msg + " [updated OK]";
            }
        } catch (SQLException se) {
            msg = msg + " exception: " + DBUtils.unchainSqlException(se);
        } catch (Throwable t) {
            msg = msg + " exception: " + t.toString();
        } finally {
            DBUtils.close(updateInfoStmt, conn);
        }
        return msg;
    }

    public String resetPassword(String forEmail, String ip) {
        String msg = "";
        String newPassword = CookieSession.newId(false);
        if (newPassword.length() > 10) {
            newPassword = newPassword.substring(0, 10);
        }
        Connection conn = null;
        PreparedStatement updateInfoStmt = null;
        try {
            conn = DBUtils.getInstance().getDBConnection();

            updateInfoStmt = DBUtils.prepareStatementWithArgs(conn, "UPDATE " + CLDR_USERS + " set password=? ,  audit=? WHERE email=? AND userlevel <"
                + LOCKED + "  AND userlevel >= " + TC, newPassword,
                "Reset: " + new Date().toString() + " by " + ip,
                forEmail);

            logger.info("** Attempt password reset " + forEmail + " from " + ip);
            int n = updateInfoStmt.executeUpdate();

            conn.commit();
            userModified(forEmail);
            if (n == 0) {
                msg = msg + "Error: no valid accounts found";
                logger.severe("Error in password reset:: 0 users updated.");
            } else if (n != 1) {
                msg = msg + " [Error in updated users!] ";
                logger.severe("Error in password reset: " + n + " updated removed!");
            } else {
                msg = msg + "OK";
                sm.notifyUser(null, forEmail, newPassword);
            }
        } catch (SQLException se) {
            SurveyLog.logException(logger, se, "Resetting password for user " + forEmail + " from " + ip);
            msg = msg + " exception";
        } catch (Throwable t) {
            SurveyLog.logException(logger, t, "Resetting password for user " + forEmail + " from " + ip);
            msg = msg + " exception: " + t.toString();
        } finally {
            DBUtils.close(updateInfoStmt, conn);
        }
        return msg;
    }

    /**
     * Lock (disable, unsubscribe) the specified account
     *
     * @param forEmail the E-mail address for the account
     * @param reason a string explaining the reason for locking
     * @param ip the current user's IP address
     * @return "OK" for success or other message for failure
     */
    public String lockAccount(String forEmail, String reason, String ip) {
        String msg = "";
        User u = this.get(forEmail);
        logger.info("** Attempt LOCK " + forEmail + " from " + ip + " reason " + reason);
        String newPassword = CookieSession.newId(false);
        if (newPassword.length() > 10) {
            newPassword = newPassword.substring(0, 10);
        }
        if (reason.length() > 500) {
            reason = reason.substring(0, 500) + "...";
        }
        Connection conn = null;
        PreparedStatement updateInfoStmt = null;
        try {
            conn = DBUtils.getInstance().getDBConnection();

            updateInfoStmt = DBUtils.prepareStatementWithArgs(conn, "UPDATE " + CLDR_USERS + " SET password=?,userlevel=" + LOCKED
                + ", audit=? WHERE email=? AND userlevel <" + LOCKED + " AND userlevel >= " + TC,
                newPassword,
                "Lock: " + new Date().toString() + " by " + ip + ":" + reason,
                forEmail);

            int n = updateInfoStmt.executeUpdate();

            conn.commit();
            userModified(forEmail);
            if (n == 0) {
                msg = "Error: no valid accounts found";
                logger.severe("Error in LOCK:: 0 users updated.");
            } else if (n != 1) {
                msg = "[Error in updated users!] ";
                logger.severe("Error in LOCK: " + n + " updated removed!");
            } else {
                msg = "OK"; /* must be exactly "OK"! */
                MailSender.getInstance().queue(null, 1, "User Locked: " + forEmail, "User account locked: " + ip + " reason=" + reason + " - " + u);
            }
        } catch (SQLException se) {
            SurveyLog.logException(logger, se, "Locking account for user " + forEmail + " from " + ip);
            msg += " SQL Exception";
        } catch (Throwable t) {
            SurveyLog.logException(logger, t, "Locking account for user " + forEmail + " from " + ip);
            msg += " Exception: " + t.toString();
        } finally {
            DBUtils.close(updateInfoStmt, conn);
        }
        return msg;
    }

    private void userModified(String forEmail) {
        User u = get(forEmail);
        if (u != null) userModified(u);
    }

    private void userModified(User u) {
        userModified(u.id);
    }

    public String getPassword(WebContext ctx, int theirId) {
        ResultSet rs = null;
        Statement s = null;
        String result = null;
        Connection conn = null;
        if (ctx != null) {
            logger.info("UR: Attempt getPassword by " + ctx.session.user.email + ": of #" + theirId);
        }
        try {
            conn = DBUtils.getInstance().getAConnection();
            s = conn.createStatement();
            rs = s.executeQuery("SELECT password FROM " + CLDR_USERS + " WHERE id=" + theirId);
            if (!rs.next()) {
                if (ctx != null)
                    ctx.println("Couldn't find user.");
                return null;
            }
            result = rs.getString(1);
            if (rs.next()) {
                if (ctx != null) {
                    ctx.println("Matched duplicate user (?)");
                }
                return null;
            }
        } catch (SQLException se) {
            logger.severe("UR:  exception: " + DBUtils.unchainSqlException(se));
            if (ctx != null)
                ctx.println(" An error occured: " + DBUtils.unchainSqlException(se));
        } catch (Throwable t) {
            logger.severe("UR:  exception: " + t.toString());
            if (ctx != null)
                ctx.println(" An error occured: " + t.toString());
        } finally {
            DBUtils.close(s, conn);
        }
        return result;
    }

    public static String makePassword(String email) {
        return CookieSession.newId(false).substring(0, 9);
    }

    /**
     * Create a new user
     *
     * @param ctx - webcontext if available
     * @param u prototype - used for user information, but not for return value
     * @return the new User object for the new user, or null for failure
     */
    public User newUser(WebContext ctx, User u) {
        final boolean hushUserMessages = CLDRConfig.getInstance().getEnvironment() == Environment.UNITTEST;
        u.email = normalizeEmail(u.email);
        // prepare quotes
        u.email = u.email.replace('\'', '_').toLowerCase();
        u.org = u.org.replace('\'', '_');
        u.name = u.name.replace('\'', '_');
        u.locales = (u.locales == null) ? "" : u.locales.replace('\'', '_');
        u.locales = LocaleNormalizer.normalizeQuietly(u.locales);

        Connection conn = null;
        PreparedStatement insertStmt = null;
        try {
            conn = DBUtils.getInstance().getDBConnection();
            insertStmt = conn.prepareStatement(SQL_insertStmt);
            insertStmt.setInt(1, u.userlevel);
            DBUtils.setStringUTF8(insertStmt, 2, u.name);
            insertStmt.setString(3, u.org);
            insertStmt.setString(4, u.email);
            insertStmt.setString(5, u.getPassword());
            insertStmt.setString(6, u.locales);
            if (!insertStmt.execute()) {
                if (!hushUserMessages) logger.info("Added.");
                conn.commit();
                if (ctx != null)
                    ctx.println("<p>Added user.<p>");
                User newu = get(u.getPassword(), u.email, FOR_ADDING); // throw away
                // old user
                updateIntLocs(newu.id, conn);
                resetOrgList(); // update with new org spelling.
                notify(newu);
                return newu;
            } else {
                if (ctx != null)
                    ctx.println("Couldn't add user.");
                conn.commit();
                return null;
            }
        } catch (SQLException se) {
            SurveyLog.logException(logger, se, "Adding User");
            logger.severe("UR: Adding " + u.toString() + ": exception: " + DBUtils.unchainSqlException(se));
        } catch (Throwable t) {
            SurveyLog.logException(logger, t, "Adding User");
            logger.severe("UR: Adding  " + u.toString() + ": exception: " + t.toString());
        } finally {
            userModified(); // new user
            DBUtils.close(insertStmt, conn);
        }

        return null;
    }

    // All of the userlevel policy is concentrated here, or in above functions
    // (search for 'userlevel')

    // * user types
    public static final boolean userIsAdmin(User u) {
        return (u != null) && (u.userlevel <= UserRegistry.ADMIN);
    }

    public static final boolean userIsTC(User u) {
        return (u != null) && (u.userlevel <= UserRegistry.TC);
    }

    public static final boolean userIsExactlyManager(User u) {
        return (u != null) && (u.userlevel == UserRegistry.MANAGER);
    }

    public static final boolean userIsManagerOrStronger(User u) {
        return (u != null) && (u.userlevel <= UserRegistry.MANAGER);
    }

    public static final boolean userIsVetter(User u) {
        return (u != null) && (u.userlevel <= UserRegistry.VETTER);
    }

    public static final boolean userIsStreet(User u) {
        return (u != null) && (u.userlevel <= UserRegistry.STREET);
    }

    public static final boolean userIsLocked(User u) {
        return (u != null) && (u.userlevel == UserRegistry.LOCKED);
    }

    public static final boolean userIsExactlyAnonymous(User u) {
        return (u != null) && (u.userlevel == UserRegistry.ANONYMOUS);
    }

    // * user rights
    /** can create a user in a different organization? */
    public static final boolean userCreateOtherOrgs(User u) {
        return userIsAdmin(u);
    }

    /** Can the user modify anyone's level? */
    static final boolean userCanModifyUsers(User u) {
        return userIsTC(u) || userIsExactlyManager(u);
    }

    static final boolean userCanEmailUsers(User u) {
        return userIsTC(u) || userIsExactlyManager(u);
    }

    /**
     * Returns true if the manager user can change the user's userlevel
     * @param managerUser the user doing the changing
     * @param targetId the user being changed
     * @param targetNewUserLevel the new userlevel of the user
     * @return true if the action can proceed, otherwise false
     */
    static final boolean userCanModifyUser(User managerUser, int targetId, int targetNewUserLevel) {
        if (targetId == ADMIN_ID) {
            return false; // can't modify admin user
        }
        if (managerUser == null) {
            return false; // no user
        }
        if (userIsAdmin(managerUser)) {
            return true; // admin can modify everyone
        }
        final User otherUser = CookieSession.sm.reg.getInfo(targetId); // TODO static
        if (otherUser == null) {
            return false; // ?
        }
        if (!managerUser.org.equals(otherUser.org)) {
            return false;
        }
        if (!userCanModifyUsers(managerUser)) {
            return false;
        }
        if (targetId == managerUser.id) {
            return false; // cannot modify self
        }
        if (targetNewUserLevel < managerUser.userlevel) {
            return false; // Cannot assign a userlevel higher than the manager
        }
        return true;
    }

    static final boolean userCanDeleteUser(User managerUser, int targetId, int targetLevel) {
        // must be at a lower level
        return (userCanModifyUser(managerUser, targetId, targetLevel) && targetLevel > managerUser.userlevel);
    }

    static final boolean userCanDoList(User managerUser) {
        return (userIsVetter(managerUser));
    }

    public static final boolean userCanCreateUsers(User u) {
        return (userIsTC(u) || userIsExactlyManager(u));
    }

    static final boolean userCanSubmit(User u) {
        if (SurveyMain.isPhaseReadonly())
            return false;
        return ((u != null) && userIsStreet(u));
    }

    /**
     * Can the user use the priority items summary page?
     *
     * @param u the user
     * @return true or false
     */
    public static final boolean userCanUseVettingSummary(User u) {
        return userIsManagerOrStronger(u);
    }

    /**
     * Can the user create snapshots in the priority items summary page?
     *
     * @param u the user
     * @return true or false
     */
    public static boolean userCanCreateSummarySnapshot(User u) {
        return userIsAdmin(u);
    }

    /**
     * Can the user monitor forum participation?
     *
     * @param u the user
     * @return true or false
     */
    public static final boolean userCanMonitorForum(User u) {
        return userIsTC(u) || userIsExactlyManager(u);
    }

    /**
     * Can the user set their interest locales (intlocs)?
     *
     * @param u the user
     * @return true or false
     */
    public static boolean userCanSetInterestLocales(User u) {
        return userIsManagerOrStronger(u);
    }

    /**
     * Can the user get a list of email addresses of participating users?
     *
     * @param u the user
     * @return true or false
     */
    public static boolean userCanGetEmailList(User u) {
        return userIsManagerOrStronger(u);
    }

    public enum ModifyDenial {
        DENY_NULL_USER("No user specified"),
        DENY_LOCALE_READONLY("Locale is read-only"),
        DENY_PHASE_READONLY("SurveyTool is in read-only mode"),
        DENY_ALIASLOCALE("Locale is an alias"),
        DENY_DEFAULTCONTENT("Locale is the Default Content for another locale"),
        DENY_PHASE_CLOSED("SurveyTool is in 'closed' phase"),
        DENY_NO_RIGHTS("User does not have any voting rights"),
        DENY_LOCALE_LIST("User does not have rights to vote for this locale"),
        DENY_PHASE_FINAL_TESTING("SurveyTool is in the 'final testing' phase");

        ModifyDenial(String reason) {
            this.reason = reason;
        }

        final String reason;

        public String getReason() {
            return reason;
        }
    }

    public static final boolean userCanModifyLocale(User u, CLDRLocale locale) {
        return (userCanModifyLocaleWhy(u, locale) == null);
    }

    public static final boolean userCanAccessForum(User u, CLDRLocale locale) {
        return (userCanAccessForumWhy(u, locale) == null);
    }

    private static Object userCanAccessForumWhy(User u, CLDRLocale locale) {
        if (u == null)
            return ModifyDenial.DENY_NULL_USER; // no user, no dice
        if (!userIsStreet(u))
            return ModifyDenial.DENY_NO_RIGHTS; // at least street level
        if (userIsAdmin(u))
            return null; // Admin can modify all
        if (userIsTC(u))
            return null; // TC can modify all
        if (SpecialLocales.getType(locale) == SpecialLocales.Type.scratch) {
            // All users can modify the sandbox
            return null;
        }
        if ((u.locales == null) && userIsManagerOrStronger(u))
            return null; // empty = ALL
        if (false || userIsExactlyManager(u))
            return null; // manager can edit all
        if (LocaleNormalizer.isAllLocales(u.locales)) {
            return null; // all
        }
        return userHasForumLocale(u, locale) ? null : ModifyDenial.DENY_LOCALE_LIST;
    }

    private static boolean userHasForumLocale(User u, CLDRLocale locale) {
        final CLDRLocale languageLocale = locale.getLanguageLocale();
        final LocaleSet authorizedLocaleSet = u.getAuthorizedLocaleSet();
        if (authorizedLocaleSet.isAllLocales()) {
            return true;
        }
        for (CLDRLocale l : authorizedLocaleSet.getSet()) {
            if (l.getLanguageLocale() == languageLocale) {
                return true;
            }
        }
        return false;
    }

    public static boolean countUserVoteForLocale(User theSubmitter, CLDRLocale locale) {
        return (countUserVoteForLocaleWhy(theSubmitter, locale) == null);
    }

    public static final ModifyDenial countUserVoteForLocaleWhy(User u, CLDRLocale locale) {
        // must not have a null user
        if (u == null)
            return ModifyDenial.DENY_NULL_USER;

        // can't vote in a readonly locale
        if (STFactory.isReadOnlyLocale(locale))
            return ModifyDenial.DENY_LOCALE_READONLY;

        // user must have street level perms
        if (!userIsStreet(u))
            return ModifyDenial.DENY_NO_RIGHTS; // at least street level

        // locales that are aliases can't be modified.
        if (sm.isLocaleAliased(locale) != null) {
            return ModifyDenial.DENY_ALIASLOCALE;
        }

        // locales that are default content parents can't be modified.
        CLDRLocale dcParent = sm.getSupplementalDataInfo().getBaseFromDefaultContent(locale);
        if (dcParent != null) {
            return ModifyDenial.DENY_DEFAULTCONTENT; // it's a defaultcontent
            // locale or a pure alias.
        }

        return u.hasLocalePermission(locale) ? null : ModifyDenial.DENY_LOCALE_LIST;
    }

    public static final ModifyDenial userCanModifyLocaleWhy(User u, CLDRLocale locale) {
        final ModifyDenial denyCountVote = countUserVoteForLocaleWhy(u, locale);

        // If we don't count the votes, modify is prohibited.
        if (denyCountVote != null) {
            return denyCountVote;
        }

        // We add more restrictions

        // Admin and TC users can always modify, even in closed state.
        if (userIsAdmin(u) || userIsTC(u))
            return null;

        // Otherwise, if closed, deny
        if (SurveyMain.isPhaseClosed())
            return ModifyDenial.DENY_PHASE_CLOSED;
        if (SurveyMain.isPhaseReadonly())
            return ModifyDenial.DENY_PHASE_READONLY;

        if (SurveyMain.isPhaseFinalTesting()) {
            return ModifyDenial.DENY_PHASE_FINAL_TESTING;
        }
        return null;
    }

    /**
     * Invalid user ID, representing NO USER.
     */
    public static final int NO_USER = -1;

    public VoterInfo getVoterToInfo(int userid) {
        return getVoterToInfo().get(userid);
    }

    // Interface for VoteResolver interface
    /**
     * Fetch the user map in VoterInfo format.
     *
     * @see #userModified()
     */
    public synchronized Map<Integer, VoterInfo> getVoterToInfo() {
        if (voterInfo == null) {
            Map<Integer, VoterInfo> map = new TreeMap<>();

            ResultSet rs = null;
            PreparedStatement ps = null;
            Connection conn = null;
            try {
                conn = DBUtils.getInstance().getAConnection();
                ps = list(null, conn);
                rs = ps.executeQuery();
                // id,userlevel,name,email,org,locales,intlocs,lastlogin
                while (rs.next()) {
                    // We don't go through the cache, because not all users may
                    // be loaded.

                    User u = new UserRegistry.User(rs.getInt(1));
                    // from params:
                    u.userlevel = rs.getInt(2);
                    u.name = DBUtils.getStringUTF8(rs, 3);
                    u.email = rs.getString(4);
                    u.org = rs.getString(5);
                    u.locales = rs.getString(6);
                    if (LocaleNormalizer.isAllLocales(u.locales)) {
                        u.locales = LocaleNormalizer.ALL_LOCALES;
                    }
                    u.intlocs = rs.getString(7);
                    u.last_connect = rs.getTimestamp(8);

                    // now, map it to a UserInfo
                    VoterInfo v = u.createVoterInfo();

                    map.put(u.id, v);
                }
                voterInfo = map;
            } catch (SQLException se) {
                logger.log(java.util.logging.Level.SEVERE,
                    "UserRegistry: SQL error trying to  update VoterInfo - " + DBUtils.unchainSqlException(se), se);
            } catch (Throwable t) {
                logger.log(java.util.logging.Level.SEVERE,
                    "UserRegistry: some error trying to update VoterInfo - " + t.toString(), t);
            } finally {
                // close out the RS
                DBUtils.close(rs, conn);
            } // end try
        }
        return voterInfo;
    }

    /**
     * VoterInfo map
     */
    private Map<Integer, VoterInfo> voterInfo = null;

    /**
     * The list of organizations
     *
     * It is necessary to call resetOrgList to initialize this list
     */
    private static String[] orgList = new String[0];

    /**
     * Get the list of organization names
     *
     * @return the String array
     */
    public static String[] getOrgList() {
        if (orgList.length == 0) {
            resetOrgList();
        }
        return orgList;
    }

    private static void resetOrgList() {
        // get all orgs in use...
        Set<String> orgs = new TreeSet<>();
        Connection conn = null;
        Statement s = null;
        try {
            conn = DBUtils.getInstance().getAConnection();
            s = conn.createStatement();
            ResultSet rs = s.executeQuery("SELECT distinct org FROM " + CLDR_USERS + " order by org");
            while (rs.next()) {
                String org = rs.getString(1);
                orgs.add(org);
            }
        } catch (SQLException se) {
            System.err.println("UserRegistry: SQL error trying to get orgs resultset for: VI " + " - "
                + DBUtils.unchainSqlException(se)/* ,se */);
        } finally {
            // close out the RS
            try {
                if (s != null) {
                    s.close();
                }
                if (conn != null) {
                    DBUtils.closeDBConnection(conn);
                }
            } catch (SQLException se) {
                System.err.println("UserRegistry: SQL error trying to close out: "
                    + DBUtils.unchainSqlException(se));
            }
        } // end try

        // get all possible VR orgs..
        Set<Organization> allvr = new HashSet<>();
        for (Organization org : Organization.values()) {
            allvr.add(org);
        }
        // Subtract out ones already in use
        for (String org : orgs) {
            allvr.remove(UserRegistry.computeVROrganization(org));
        }
        // Add back any ones not yet in use
        for (Organization org : allvr) {
            String orgName = org.name();
            orgName = UCharacter.toTitleCase(orgName, null);
            orgs.add(orgName);
        }

        orgList = orgs.toArray(orgList);
    }

    /**
     * Cache of the set of anonymous users
     */
    private Set<User> anonymousUsers = null;

    /**
     * Get the set of anonymous users, employing a cache.
     * If there aren't as many as there should be (ANONYMOUS_USER_COUNT), create some.
     * An "anonymous user" is one whose userlevel is ANONYMOUS.
     *
     * @return the Set.
     */
    public Set<User> getAnonymousUsers() {
        if (anonymousUsers == null) {
            anonymousUsers = getAnonymousUsersFromDb();
            int existingCount = anonymousUsers.size();
            if (existingCount < ANONYMOUS_USER_COUNT) {
                createAnonymousUsers(existingCount, ANONYMOUS_USER_COUNT);
                /*
                 * After createAnonymousUsers, call userModified to clear voterInfo, so it will
                 * be reloaded and include the new anonymous users. Otherwise, we would get an
                 * "Unknown voter" exception in OrganizationToValueAndVote.add when trying to
                 * add a vote for one of the new anonymous users.
                 */
                userModified();
                anonymousUsers = getAnonymousUsersFromDb();
            }
        }
        return anonymousUsers;
    }

    /**
     * Get the set of anonymous users by running a database query.
     *
     * @return the Set.
     */
    private Set<User> getAnonymousUsersFromDb() {
        Set<User> set = new HashSet<>();
        ResultSet rs = null;
        PreparedStatement ps = null;
        Connection conn = null;
        try {
            conn = DBUtils.getInstance().getAConnection();
            ps = list(null, conn);
            rs = ps.executeQuery();
            // id,userlevel,name,email,org,locales,intlocs,lastlogin
            while (rs.next()) {
                int userlevel = rs.getInt(2);
                if (userlevel == ANONYMOUS) {
                    User u = new User(rs.getInt(1));
                    u.userlevel = userlevel;
                    u.name = DBUtils.getStringUTF8(rs, 3);
                    u.email = rs.getString(4);
                    u.org = rs.getString(5);
                    u.locales = rs.getString(6);
                    if (LocaleNormalizer.isAllLocales(u.locales)) {
                        u.locales = LocaleNormalizer.ALL_LOCALES;
                    }
                    u.intlocs = rs.getString(7);
                    u.last_connect = rs.getTimestamp(8);
                    set.add(u);
                }
            }
        } catch (SQLException se) {
            logger.log(java.util.logging.Level.SEVERE,
                "UserRegistry: SQL error getting anonymous users - " + DBUtils.unchainSqlException(se), se);
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.SEVERE,
                "UserRegistry: some error getting anonymous users - " + t.toString(), t);
        } finally {
            DBUtils.close(rs, ps, conn);
        }
        return set;
    }

    /**
     * Given that there aren't enough anonymous users in the database yet, create some.
     *
     * @param existingCount the number of anonymous users that already exist
     * @param desiredCount the desired total number of anonymous users
     */
    private void createAnonymousUsers(int existingCount, int desiredCount) {
        Connection conn = null;
        Statement s = null;
        try {
            conn = DBUtils.getInstance().getDBConnection();
            s = conn.createStatement();
            for (int i = existingCount + 1; i <= desiredCount; i++) {
                /*
                 * Don't specify the user id; a new unique id will be assigned automatically.
                 * Names are like "anon#3"; emails are like "anon3@example.org".
                 */
                String sql = "INSERT INTO " + CLDR_USERS
                    + "(userlevel, name, email, org, password, locales) VALUES("
                    + ANONYMOUS + ","                // userlevel
                    + "'anon#" + i + "',"            // name
                    + "'anon" + i + "@example.org'," // email
                    + "'cldr',"                      // org
                    + "'',"                          // password
                    + "'" + LocaleNormalizer.ALL_LOCALES + "')";      // locales
                s.execute(sql);
            }
            conn.commit();
        } catch (SQLException se) {
            logger.log(java.util.logging.Level.SEVERE,
                "UserRegistry: SQL error creating anonymous users - " + DBUtils.unchainSqlException(se), se);
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.SEVERE,
                "UserRegistry: some error creating anonymous users - " + t.toString(), t);
        } finally {
            DBUtils.close(s, conn);
        }
    }

    /**
     * Create a test user, for unit testing only
     *
     * @param name
     * @param org
     * @param locales
     * @param level
     * @param email
     * @return the User object, or null for failure such as for invalid locales
     */
    public User createTestUser(String name, String org, String locales, Level level, String email) {
        final LocaleNormalizer locNorm = new LocaleNormalizer();
        final Organization organization = Organization.fromString(org);
        final String normLocales = locNorm.normalizeForSubset(locales, organization.getCoveredLocales());
        if (locNorm.hasMessage()) {
            logger.log(java.util.logging.Level.SEVERE, locNorm.getMessagePlain());
            return null;
        }
        UserRegistry.User proto = getEmptyUser();
        proto.email = email;
        proto.name = name;
        proto.org = org;
        proto.setPassword(UserRegistry.makePassword(proto.email));
        proto.userlevel = level.getSTLevel();
        proto.locales = normLocales;

        User u = newUser(null, proto);
        return u;
    }

    public static JSONObject getLevelMenuJson(User me) throws JSONException {
        final VoteResolver.Level myLevel = VoteResolver.Level.fromSTLevel(me.userlevel);
        final Organization myOrganization = me.getOrganization();
        JSONObject levels = new JSONObject();
        for (VoteResolver.Level v : VoteResolver.Level.values()) {
            if (v == VoteResolver.Level.anonymous) {
                continue;
            }
            int number = v.getSTLevel(); // like 999
            JSONObject jo = new JSONObject();
            jo.put("name", v.name()); // like "locked"
            jo.put("string", UserRegistry.levelToStr(number)); // like "999: (LOCKED)"
            jo.put("canCreateOrSetLevelTo", myLevel.canCreateOrSetLevelTo(v));
            jo.put("isManagerFor", myLevel.isManagerFor(myOrganization, v, myOrganization));
            levels.put(String.valueOf(number), jo);
        }
        return levels;
    }
}
