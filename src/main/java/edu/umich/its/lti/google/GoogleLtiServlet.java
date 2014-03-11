/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2013 The Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.opensource.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/
package edu.umich.its.lti.google;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.ResourceBundle;
import java.util.StringTokenizer;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.protocol.HTTP;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.drive.model.PermissionList;

import edu.umich.its.google.oauth.GoogleSecurity;
import edu.umich.its.google.oauth.GoogleServiceAccount;
import edu.umich.its.lti.GoogleCache;
import edu.umich.its.lti.TcSessionData;
import edu.umich.its.lti.TcSiteToGoogleLink;
import edu.umich.its.lti.TcSiteToGoogleStorage;
import edu.umich.its.lti.utils.OauthCredentials;
import edu.umich.its.lti.utils.RequestSignatureUtils;
import edu.umich.its.lti.utils.RosterClientUtils;

/**
 * Servlet with doGet() that allows browser to request an access token or to
 * open a JSP page, and doPost() to get request from LTI Client to load the
 * resources associated with the client's site.
 * 
 * NOTE: doGet() needs security to ensure the request is correct.  This can be
 * done confirming details sent by the client server, client site ID and user. 
 * 
 * @author Raymond Naseef
 *
 **/
/**
 * @author pushyami
 *
 */
/**
 * @author pushyami
 * 
 */
public class GoogleLtiServlet extends HttpServlet {

	private static String GOOGLE_DRIVE = "";
	private static String LINK_GOOGLE_DRIVE = "";
	static ResourceBundle resource;
	//to keep track of the removal of permissions failure.
	static int flag=0;
	

	// Enum ---------------------------------------------------------

	// Specifications for different JSP pages used by the LTI. This is passed
	// to root JSP files with properties to manage display of the page contents.
	public enum JspPage {

		// Home page shows Google Resources with functions to act upon them
		Home("pages/show-google-drive.jsp", GOOGLE_DRIVE, null),
		// Link Folder page shows instructor folders they own, so they can link
		// 1+ folders to the site
		LinkFolder("pages/link-google-drive.jsp", LINK_GOOGLE_DRIVE,
				new String[] { "Instructor" });

		// Instance variables -----------------------------

		private String pageFileUrl;
		private String pageTitle;
		private String[] roles;

		// Constructors -----------------------------------

		private JspPage(String pageFileUrlValue, String pageTitleValue,
				String[] rolesValue) {
			pageTitle = pageTitleValue;
			pageFileUrl = pageFileUrlValue;
			roles = rolesValue;
		}

		// Public methods ---------------------------------

		public String getPageFileUrl() {
			return pageFileUrl;
		}

		public String getPageTitle() {
			return pageTitle;
		}

		public String[] getRoles() {
			return roles;
		}

		public boolean verifyAllowedRoles(String[] userRoles) {
			boolean result = false;
			String[] allowedRoles = getRoles();
			if (allowedRoles != null) {
				// Only proceed if there are user roles to check; otherwise,
				// user does not have any allowed role
				if (userRoles != null) {
					for (int allowedRoleIdx = 0; !result
							&& (allowedRoleIdx < allowedRoles.length); allowedRoleIdx++) {
						String allowedRole = allowedRoles[allowedRoleIdx];
						for (int userRoleIdx = 0; !result
								&& (userRoleIdx < userRoles.length); userRoleIdx++) {
							String userRole = userRoles[userRoleIdx];
							result = userRole.equals(allowedRole);
						}
					}
				}
			} else {
				// Page with null roles are open to all
				result = true;
			}
			return result;
		}
	}

	// Constants -----------------------------------------------------

	private static final long serialVersionUID = -21239787L;
	private static final Log M_log = LogFactory.getLog(GoogleLtiServlet.class);

	private static final String SESSION_ATTR_TC_DATA = "TcSessionData";
	private static final String GOOGLE_SERVICE_ACCOUNT_PROPS_PREFIX = "googleDriveLti";
	private static final String EXPECTED_LTI_MESSAGE_TYPE = "basic-lti-launch-request";
	private static final String EXPECTED_LTI_VERSION = "LTI-1p0";
	private static final String JSP_VAR_GOOGLE_DRIVE_CONFIG_JSON = "GoogleDriveConfigJson";
	private static final String PARAMETER_ACTION = "requested_action";
	// Special request to monitor this service is alive: this returns "Hi"
	private static final String PARAM_ACTION_VERIFY_SERVICE_IS_ALIVE = "checkServiceIsAlive";
	private static final String PARAM_ACTION_CHECK_BACK_BUTTON = "checkBackButton";
	private static final String PARAM_ACTION_LINK_GOOGLE_FOLDER = "linkGoogleFolder";
	private static final String PARAM_ACTION_UNLINK_GOOGLE_FOLDER = "unlinkGoogleFolder";
	private static final String PARAM_ACTION_GIVE_ROSTER_ACCESS = "giveRosterAccess";
	private static final String PARAM_ACTION_GIVE_CURRENT_USER_ACCESS = "giveCurrentUserAccess";
	private static final String PARAM_ACTION_REMOVE_ROSTER_ACCESS = "removeRosterAccess";
	private static final String PARAM_ACTION_GET_ACCESS_TOKEN = "getAccessToken";
	private static final String PARAM_ACTION_OPEN_PAGE = "openPage";
	private static final String PARAM_OPEN_PAGE_NAME = "pageName";
	private static final String PARAM_ACCESS_TOKEN = "access_token";
	private static final String PARAM_OWNER_ACCESS_TOKEN ="getOwnerToken";
	private static final String PARAM_INSTRUCTOR_ACCESS_TOKEN_SETTING_SERVICE ="getIntructorTokenSS";
	private static final String PARAM_FILE_ID = "file_id";
	private static final String PARAM_SEND_NOTIFICATION_EMAILS = "send_notification_emails";
	private static final String PARAM_TP_ID = "tp_id";
	private static final String FOLDER_TITLE = "folderTitle";
	private static final String SUCCESS = "SUCCESS";
	private static final String NOSUCCESS = "NOSUCCESS";

	// Constructors --------------------------------------------------

	public GoogleLtiServlet() {
	}

	// Public methods ------------------------------------------------

	public void doError(HttpServletRequest request,
			HttpServletResponse response, String message)
					throws java.io.IOException {
		String returnUrl = request
				.getParameter("launch_presentation_return_url");
		if (!getIsEmpty(returnUrl)) {
			// Looks like client is LTI consumer: return error message in URL
			if (returnUrl.indexOf('?') > 1) {
				returnUrl += "&lti_msg="
						+ URLEncoder.encode(message, HTTP.UTF_8);
			} else {
				returnUrl += "?lti_msg="
						+ URLEncoder.encode(message, HTTP.UTF_8);
			}
			response.sendRedirect(returnUrl);
		} else {
			// Client not recognized: simply print the message as the response
			PrintWriter out = response.getWriter();
			out.println(message);
		}
	}

	// Protected methods ---------------------------------------------

	/**
	 * Returns a simple page indicating they found this servlet.
	 */
	@Override
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) {
		try {
			String requestedAction = request.getParameter(PARAMETER_ACTION);
			// This is used to monitor this service is alive: return "Hi"
			if (PARAM_ACTION_VERIFY_SERVICE_IS_ALIVE.equals(requestedAction)) {
				response.getWriter().print("Hi");
				return;
			}
			TcSessionData tcSessionData = retrieveLockFromSession(request);
			if (!verifyGet(request, response, tcSessionData, requestedAction)) {
				return; // Quick return to simplify code
			}
			if (PARAM_ACTION_LINK_GOOGLE_FOLDER.equals(requestedAction)) {
				linkGoogleFolder(request, response, tcSessionData);
			} else if (PARAM_ACTION_CHECK_BACK_BUTTON.equals(requestedAction)) {
				checkBackButtonHit(request, response, tcSessionData);
			} else if (PARAM_ACTION_UNLINK_GOOGLE_FOLDER
					.equals(requestedAction)) {
				unlinkGoogleFolder(request, response, tcSessionData);
			} else if (PARAM_ACTION_GIVE_ROSTER_ACCESS
					.equals(requestedAction)) {
				insertRosterPermissions(request, response, tcSessionData);
			} else if (PARAM_ACTION_GIVE_CURRENT_USER_ACCESS
					.equals(requestedAction)) {
				insertCurrentUserPermissions(request, response, tcSessionData);
			} else if (PARAM_ACTION_REMOVE_ROSTER_ACCESS
					.equals(requestedAction)) {
				removePermissions(request, response, tcSessionData);
			} else if (PARAM_ACTION_GET_ACCESS_TOKEN.equals(requestedAction)) {
				getGoogleAccessToken(request, response, tcSessionData);
			}else if (PARAM_OWNER_ACCESS_TOKEN.equals(requestedAction)) {
				getOwnerAccessToken(request, response, tcSessionData);
			}else if (PARAM_INSTRUCTOR_ACCESS_TOKEN_SETTING_SERVICE.equals(requestedAction)){
				getInstructorEmailAddressFromSettingService(request, response, tcSessionData);
			}else if (PARAM_ACTION_OPEN_PAGE.equals(requestedAction)) {
				loadJspPage(request, response, tcSessionData);
			} else {
				M_log.warn("Request action unknown: \"" + requestedAction
						+ "\"");
			}
		} catch (Exception e) {
			// this catches ServletException and IOException
			M_log.error("GET request failed", e);

		}
	}

	/**
	 * Verifies if the request is valid; if so, this initializes Google Drive so
	 * the browser may make requests to see resources associated with the given
	 * ToolConsumer(TC) site.
	 */
	@Override
	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) {
		try {
			if (verifyPost(request, response)) {
				bundleManipulation(request);
				TcSessionData tcSessionData = lockInSession(request);
				if (tcSessionData==null) {
					doError(request, response,
							resource.getString("gd.post.failure"));
					return;
				}
				String googleConfigJson = GoogleConfigJsonWriter
						.getGoogleDriveConfigJsonScript(tcSessionData);
				request.setAttribute(JSP_VAR_GOOGLE_DRIVE_CONFIG_JSON,
						googleConfigJson);
				TcSiteToGoogleLink link = TcSiteToGoogleStorage
						.getLinkingFromSettingService(tcSessionData);
				if (link != null) {
					loadJspPage(request, response, tcSessionData, JspPage.Home);
				} else if(tcSessionData.getIsInstructor()) {
					loadJspPage(request, response, tcSessionData,
							JspPage.LinkFolder);
					}
				else {
					loadJspPage(request, response, tcSessionData, JspPage.Home);
				}
			}
		} catch (Exception e) {
			M_log.error("POST request failed", e);
		}
	}

	// Private methods ----------------------------------------------
	/**
	 * gets the locale information and instantiate the Resource bundle to handle
	 * the localization. Only supports language/country and not region in Locale
	 * object
	 * 
	 * @param tcSessionData
	 */
	private void bundleManipulation(HttpServletRequest request) {
		String language = null;
		String country = null;
		String locale = request.getParameter("launch_presentation_locale");
		StringTokenizer tempStringTokenizer = new StringTokenizer(locale, "_");
		if (tempStringTokenizer.hasMoreTokens()) {
			language = tempStringTokenizer.nextToken();
		}
		if (tempStringTokenizer.hasMoreTokens()) {
			country = tempStringTokenizer.nextToken();
		}

		resource = ResourceBundle.getBundle("googleDriveLTIProps", new Locale(
				language, country));
		GOOGLE_DRIVE = resource.getString("gd.header1.linked.view");
		LINK_GOOGLE_DRIVE = resource.getString("gd.header1.linking.view");

	}

	/**
	 * Saves relationship of folder and site in LTI setting service, and returns
	 * the updated Google Drive Configuration json to the browser.
	 * 
	 * @param request
	 * @param response
	 * @param tcSessionData
	 * @throws Exception 
	 */
	private void linkGoogleFolder(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData) throws Exception  {
		String folderId = request.getParameter(PARAM_FILE_ID);
		TcSiteToGoogleLink newLink = new TcSiteToGoogleLink(
				tcSessionData.getContextId(),
				tcSessionData.getUserEmailAddress(), tcSessionData.getUserId(),
				folderId);
		// relationship between the folder and the site is being set in the
		// Setting service.
		try {
			if(TcSiteToGoogleStorage.setLinkingToSettingService(tcSessionData,
					newLink)) {
			response.getWriter().print(
					GoogleConfigJsonWriter
					.getGoogleDriveConfigJson(tcSessionData));
			}
			else {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				response.getWriter().print(resource.getString("gd.error.linking.setting.service"));
			}
				
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			response.getWriter().print(resource.getString("gd.error.linking.setting.service"));
			M_log.error("Failed to set the shared folder information into the Setting service",e);
		}
	}

	/**
	 * Removes relationship of folder and site from the database, and returns
	 * the updated Google Drive Configuration json to the browser.
	 * 
	 * @param request
	 * @param response
	 * @param tcSessionData
	 * @throws Exception 
	 */
	private void unlinkGoogleFolder(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData) throws Exception {
		try {
			if(
			TcSiteToGoogleStorage
					.setUnLinkingToSettingService(tcSessionData)) {
				response.getWriter().print(
						GoogleConfigJsonWriter
						.getGoogleDriveConfigJson(tcSessionData));
			}
			else {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				response.getWriter().print(resource.getString("gd.error.unlinking.setting.service"));
			}
			
		} catch (Exception e) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			response.getWriter().print(resource.getString("gd.error.unlinking.setting.service"));
			M_log.error("Failed to Unshare info into the Setting Service");
		}

	}

	/**
	 * If the instructor hit back button in the browser from the shared view
	 * this checks to see if the settings service has any thing in it. If it
	 * does then it will redirect to the Shared view. This check here eliminate
	 * a potential bug as instructor should only go back to the create and
	 * shared view is by unlinking the folder and not hitting back button.
	 * 
	 * @param request
	 * @param response
	 * @param tcSessionData
	 * @throws IOException
	 * @throws ServletException
	 */
	private void checkBackButtonHit(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData)
					throws IOException, ServletException {
		TcSiteToGoogleLink link = TcSiteToGoogleStorage
				.getLinkingFromSettingService(tcSessionData);
		if (link == null) {
			response.getWriter().print(NOSUCCESS);
		} else {
			response.getWriter().print(SUCCESS);
		}
	}

	/**
	 * This records TcSessionData holding LTI session specific data, and returns
	 * it for sharing it's unique key with the browser, ensuring requests coming
	 * from the browser carrying this key are for the correct LTI session.
	 * 
	 * @param request
	 *            HttpServletRequest holding the session
	 */
	private TcSessionData lockInSession(HttpServletRequest request) {
		// Store TC data in session.
		TcSessionData result = null;
		String ltiSecret = getGoogleServiceAccount().getLtiSecret();
		String ltiKey = getGoogleServiceAccount().getLtiKey();
		String ltiKeyFromLaunch = request.getParameter("oauth_consumer_key");
		if((ltiKey.equals(ltiKeyFromLaunch))) {
			OauthCredentials oauthCredentials = new OauthCredentials(ltiKey,ltiSecret);
			result = new TcSessionData(request,oauthCredentials);
		}else {
			M_log.error("The LTI key from the launch of the application is not same as LTI key from the properties file");
			return result;
		}
		if (getIsEmpty(result.getUserEmailAddress())) {
			throw new IllegalStateException(
					"Google Drive LTI was opened by user without email address. Please verify the tool is configured by checking the SEND EMAIL ADDRESSES TO EXTERNAL TOOL option for course (context_id): "
							+ result.getContextId());
		}
		request.setAttribute(SESSION_ATTR_TC_DATA, result);
		request.getSession().setAttribute(SESSION_ATTR_TC_DATA, result);
		return result;
	}

	
	/**
	 * 
	 * @param request
	 *            HttpServletRequest holding the session
	 * @return TcSessionData for this session; null if there is none
	 */
	private TcSessionData retrieveLockFromSession(HttpServletRequest request) {
		TcSessionData result = (TcSessionData) request.getSession()
				.getAttribute(SESSION_ATTR_TC_DATA);
		request.setAttribute(SESSION_ATTR_TC_DATA, result);
		return result;
	}

	private boolean verifyGet(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData,
			String requestedAction) throws ServletException, IOException {
		boolean result = false;
		StringBuilder sb = new StringBuilder();
		if (tcSessionData != null) {
			String requestTpId = request.getParameter(PARAM_TP_ID);
			if (!getIsEmpty(requestTpId)
					&& tcSessionData.matchTpId(requestTpId)) {
				result = true;
				return result;
			} else {
				sb.append("A request \"");
				sb.append(requestedAction);
				sb.append("\" was made to Google Drive LTI with unmatched ");
				sb.append(" authority key: given \"");
				sb.append(requestTpId);
				sb.append("\", expected \"");
				sb.append(tcSessionData.getId());
				sb.append("\".");
				M_log.warn(sb.toString());
				doError(request, response,
						"The server failed to match the authority key for this request.");
			}
		} else {
			sb.append("A request \"");
			sb.append(requestedAction);
			sb.append("\" was made to Google Drive LTI, and there is no data in the session from a post made by Tool Consumer(TC).");
			M_log.warn(sb.toString());
			doError(request, response,
					"No action taken: the request could not be verified in this session.");
		}
		return result;
	}

	/**
	 * This verifies the post has expected parameters from valid LTI client, and
	 * the request has matching signature. If not verified, this sends error to
	 * client; caller should not attempt to proceed.
	 */
	private boolean verifyPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		// ResourceBundle properties = ResourceBundle.getBundle("ltis");
		// 1 - verify the expected parameters exist
		boolean result = EXPECTED_LTI_MESSAGE_TYPE.equals(request
				.getParameter("lti_message_type"))
				&& EXPECTED_LTI_VERSION.equals(request
						.getParameter("lti_version"))
						&& !getIsEmpty(request.getParameter("oauth_consumer_key"))
						&& !getIsEmpty(request.getParameter("resource_link_id"));
		if (!result) {
			doError(request, response,
					"Post apparently not from LTI Consumer, as parameters are missing or invalid.");
		}
		// 2 - verify signature
		result = RequestSignatureUtils.verifySignature(request,
				request.getParameter("oauth_consumer_key"),
				getGoogleServiceAccount().getLtiSecret());
		if (!result) {
			doError(request, response, "Request signature is invalid.");
		}
		return result;
	}

	/**
	 * @return true if the given object is null or trimmed = ""
	 */
	private boolean getIsEmpty(String item) {
		return (item == null) || (item.trim().equals(""));
	}

	/**
	 * This puts the Google Configuration object into the request, for inserting
	 * onto the HTML page.
	 * 
	 * @param request
	 */
	private void retrieveGoogleDriveConfigFromSession(
			TcSessionData tcSessionData, HttpServletRequest request)
					throws IOException {
		request.setAttribute(JSP_VAR_GOOGLE_DRIVE_CONFIG_JSON,
				GoogleConfigJsonWriter
				.getGoogleDriveConfigJsonScript(tcSessionData));
	}

	private void insertRosterPermissions(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData)
					throws ServletException, IOException {
		HashMap<String, HashMap<String, String>> roster = getRoster(request, tcSessionData);
		insertPermissions(request, response, tcSessionData, roster);
		// Title set in request by insertPermissions: get and clear it
		request.removeAttribute(FOLDER_TITLE);
		response.getWriter().print(SUCCESS);
	}

	private void insertCurrentUserPermissions(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData)
					throws Exception {
		String userRole=null;
		String emailAddress = tcSessionData.getUserEmailAddress();
	 if(tcSessionData.getIsInstructor()) {
			userRole="Instructor";
		}
	 else
	 {
		 userRole="Learner";
	 }
		if (getIsEmpty(emailAddress)) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			response.getWriter().print(resource.getString("gd.error.permission.single.user"));
			M_log.error("Error: unable to handle permissions - the ToolConsumer(TC) did not sent the current user's email address.");
			return;
		}
		List<String> emailAddresses = new ArrayList<String>();
		HashMap<String, String> singleUser = new HashMap<String, String>();
		singleUser.put(emailAddress, userRole);
		emailAddresses.add(emailAddress);
		if (insertCurrentPermissionsForSingleUser(request, response, tcSessionData, singleUser) == 1) {
			response.getWriter().print(SUCCESS);
		}
		else {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			response.getWriter().print(resource.getString("gd.error.permission.single.user"));
			
		}
	}
	
	private int insertCurrentPermissionsForSingleUser(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData,
			HashMap<String,String> singleUser) throws Exception {
		int result = 0;
		try {
			if (!validatePermissionsRequiredParams(request, response,
					tcSessionData)) {
				return 0;
			}
			FolderPermissionsHandler handler = getHandler(request, response,
					tcSessionData);
			// google file object
			File file = handler.getFile();
			if (file == null) {
				M_log.error("Error: unable to modify Google Folder permissions, as the folder was not retrieved from Google Drive.");
				return 0; // Quick return to simplify code
			}
			// Ugly way to pass title to the calling method
			request.setAttribute(FOLDER_TITLE, file.getTitle());
			boolean sendNotificationEmails = Boolean.parseBoolean(request
					.getParameter(PARAM_SEND_NOTIFICATION_EMAILS));
			// Insert permission for each given person
			for ( Entry<String, String> entry : singleUser.entrySet()) {
			    String emailAddress = entry.getKey();
			    String roles = entry.getValue();
			    if (!getIsEmpty(emailAddress)
						&& !handler.getIsInstructor(emailAddress)) {
					// If result not null, the user has permission >= inserted
					if (null != handler.insertPermission(emailAddress,roles,
							sendNotificationEmails)) {
						result++;
					}
				}
			}
			
		} catch (Exception err) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			M_log.error("Error occurred while inserting permission for single user", err);
		}
		return result;
	}
	
	/**
	 * Gives people with the given email addresses read-only access to students
	 * for the given shared folder. Multiple instructors in the roster who are not owner of the shared folder
	 * are given can edit access.The instructor who is expected to be the owner of the shared folder 
	 * their permissions are not touched.
	 * 
	 * If people already have higher permissions, this will not affect that.
	 * This would be the case because the instructor already gave them those
	 * permissions.
	 * 
	 * @return Number of permissions that were successfully inserted
	 */
	
	private int insertPermissions(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData,
			HashMap<String,HashMap<String, String>> roster) throws ServletException, IOException {
		int result = 0;
		try {
			if (!validatePermissionsRequiredParams(request, response,
					tcSessionData)) {
				return 0;
			}
			FolderPermissionsHandler handler = getHandler(request, response,
					tcSessionData);
			// google file object
			File file = handler.getFile();
			if (file == null) {
				M_log.error("Error: unable to modify Google Folder permissions, as the folder was not retrieved from Google Drive.");
				return 0; // Quick return to simplify code
			}
			// Ugly way to pass title to the calling method
			request.setAttribute(FOLDER_TITLE, file.getTitle());
			boolean sendNotificationEmails = Boolean.parseBoolean(request
					.getParameter(PARAM_SEND_NOTIFICATION_EMAILS));
			// Insert permission for each given person
			for ( Entry<String, HashMap<String, String>> entry : roster.entrySet()) {
			    String emailAddress = entry.getKey();
			    HashMap<String, String> value = entry.getValue();
			    String roles = value.get("role");
			    if (!getIsEmpty(emailAddress)
						&& !handler.getIsInstructor(emailAddress)) {
					// If result not null, the user has permission >= inserted
					if (null != handler.insertPermission(emailAddress,roles,
							sendNotificationEmails)) {
						result++;
					}
				}
			}
			
		} catch (Exception err) {
			M_log.warn("Error insertPermissions():", err);
		}
		return result;
	}

	private FolderPermissionsHandler getHandler(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData)
					throws ServletException, IOException {
		FolderPermissionsHandler result = null;
		String siteId = tcSessionData.getContextId();
		String fileId = request.getParameter(PARAM_FILE_ID);
		// setting stuff
		TcSiteToGoogleLink link = TcSiteToGoogleStorage
				.getLinkingFromSettingService(tcSessionData);
		String instructorEmailAddress = "";
		if (link != null) {
			instructorEmailAddress = link.getUserEmailAddress();
		} else {
			link = GoogleCache.getInstance().getLinkForSite(siteId);
			if (link == null) {
				StringBuilder sb = new StringBuilder();
				sb.append("Error: cannot modify permissions to folder #");
				sb.append(fileId);
				sb.append(" - did not find link with course #");
				sb.append(tcSessionData.getContextId());
				M_log.warn(sb.toString());
				return null;
			}
			instructorEmailAddress = link.getUserEmailAddress();
		}

		GoogleCredential googleCredential = null;
		if (instructorEmailAddress.equalsIgnoreCase(tcSessionData
				.getUserEmailAddress())) {
			// Logged in user is instructor: use their access token
			googleCredential = getGoogleCredential(request);
		} else {
			// This is unlikely to happen for whole roster, but will be
			// useful for code modifying a single student's/ or other instructor 
			//permissions in roster
			googleCredential = GoogleSecurity.authorize(
					getGoogleServiceAccount(), instructorEmailAddress);
		}
		Drive drive = GoogleSecurity.getGoogleDrive(googleCredential);
		result = new FolderPermissionsHandler(link, drive, fileId);
		return result;
	}
	
	/**
	 * This function is useful in finding the list of users on file
	 * or folder  and from that list we are able to get the owner of the file/folder
	 * and grab owner's email address and generate the Owner access token 
	 * to delete a file/folder
	 * by a user who only has can edit right on the folder/file.
	 * @throws Exception 
	 * 
	 */
	
	private void getOwnerAccessToken(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData) throws Exception {
		String fileId = request.getParameter(PARAM_FILE_ID);
		String userEmailAddress = tcSessionData.getUserEmailAddress();
		String ownerOfTheFileEmailAddress=null;
		GoogleCredential credential = GoogleSecurity.authorize(
				getGoogleServiceAccount(), userEmailAddress);
		Drive drive = GoogleSecurity.getGoogleDrive(credential);
		try {
			PermissionList list = drive.permissions().list(fileId).execute();
            List<Permission> items = list.getItems();
            for (Permission permission : items) {
            	String role = permission.getRole();
            	if(role.equals("owner")) {
            		 ownerOfTheFileEmailAddress = permission.getEmailAddress();
            		 break;
            	}
			}
            getGoogleOwnerAccessToken(request, response, tcSessionData,ownerOfTheFileEmailAddress);
			
		} catch (Exception e) {
			M_log.error("Failed to get the owner email address for the a given folder",e);
			response.getWriter().print("ERROR");
		}
		
	}

	/**
	 * Removes read-only access to the given folder to people in the roster.
	 * Permissions for owners of the folder, and for the instructor, are not
	 * touched.
	 * 
	 * This uses a workaround to modify permissions, as there is no way to
	 * request permissions per file and user. Inserting a permission returns the
	 * existing permission if the user already has permissions, so inserting and
	 * deleting permission is the simplest method available for removing a
	 * person's permissions to the file.
	 */
	
	private void removePermissions(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData)
					throws Exception {
		try {
			if (!validatePermissionsRequiredParams(request, response,
					tcSessionData)) {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				response.getWriter().print(resource.getString("permission.error.six"));
				return;
			}
			FolderPermissionsHandler handler = getHandler(request, response,
					tcSessionData);
			File file = handler.getFile();
			if (file == null) {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				M_log.error("Error: unable to modify Google Folder permissions, as the folder was not retrieved from Google Drive.");
				response.getWriter().print(resource.getString("permission.error.six"));
				return; // Quick return to simplify code
			}
			// Get credential for the instructor owning the folder
			boolean sendNotificationEmails = Boolean.parseBoolean(request
					.getParameter(PARAM_SEND_NOTIFICATION_EMAILS));
			// Insert permission for each person in the roster
			HashMap<String,HashMap<String, String>> roster = getRoster(request,tcSessionData);
			removePermissionCheck(handler, sendNotificationEmails, roster, response);
		} catch (Exception err) {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			response.getWriter().print(resource.getString("permission.error.six"));
			M_log.error("Removal of permission for the folders is unsuccesful",err);
		}

	}

	/*
	 * Extracted to separate method to make the recursive call to handle the if removal  permissions is a failure 
	 * and try again for the second time and then permission is failure again then display the user with the useful message.
	 */
	private void removePermissionCheck(FolderPermissionsHandler handler,
			boolean sendNotificationEmails,
			HashMap<String, HashMap<String, String>> roster, HttpServletResponse response) throws Exception {
		int rostersize = roster.size();
		int updateCount = 0;
		for ( Entry<String, HashMap<String, String>> entry : roster.entrySet()) {
		    String emailAddress = entry.getKey();
		    HashMap<String, String> value = entry.getValue();
		    String roles = value.get("role");
		    if (!getIsEmpty(emailAddress)
					&& !handler.getIsInstructor(emailAddress)) {
				// If result not null, the user has permission >= inserted
				Permission permission = handler.insertPermission(emailAddress,roles,
						sendNotificationEmails);
				if (permission != null) {
					if (handler.removePermission(permission.getId())) {
						updateCount++;
					}
				}
				
			}
		}
		if(updateCount==(rostersize-1)) {
		response.setStatus(HttpServletResponse.SC_OK);
		response.getWriter().print(SUCCESS);
		}
		else {
			if(flag<1) {
			flag++;
			removePermissionCheck(handler, sendNotificationEmails, roster, response);
			}
			else {
				flag=0;
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				response.getWriter().print(resource.getString("permission.error.six"));
			}
			
		}
	}

	/**
	 * Send response to the browser with the access token for Google Drive and the given user email address.
	 * 
	 * If the user's email address is empty, write a message to the log and to the response.
	 * 
	 * If the access token string returned from Google is null, an error has occurred.  Most likely it means
	 * the user doesn't have a valid Google account.  Write a message to the log, then send the string "ERROR" as the
	 * response.
	 */
	private void getGoogleAccessToken(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData)
					throws IOException {
		String userEmailAddress = tcSessionData.getUserEmailAddress();

		if (getIsEmpty(userEmailAddress)) {
			logErrorWritingResponse(
					response,
					"Error: unable to get access token - the ToolProvider(TP) server does not know the user's email address.");
			return;
		}

		// Throws exception for bad email and other reasons.  Should we catch it?
		String accessToken =  GoogleSecurity.getGoogleAccessToken(
			getGoogleServiceAccount(), userEmailAddress);

		if (accessToken != null) {
			response.getWriter().print(accessToken);
		} else {
			M_log.warn("ERROR: User \""
					+ tcSessionData.getUserSourceDid()
					+ "\" does not have a valid Google account for Google Drive LTI.  Unable to get access token.  (Email: "
					+ userEmailAddress + "; ID: " + tcSessionData.getUserId()
					+ ")");
			response.getWriter().print("ERROR");
		}
	}
	/**
	 * This case help to determines if the  404 error occurs while showing a shared folder is due to user 
	 * don't has permission or shared folder has been deleted from the google drive interface. 
	 * This function call check to get the instructor email address from the setting service and generates a token
	 * and with generated token check if the shared folder exist or not.
	 * 
	 */
	
	private void getInstructorEmailAddressFromSettingService(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData) throws Exception  {
		try {
			TcSiteToGoogleLink link = TcSiteToGoogleStorage
			.getLinkingFromSettingService(tcSessionData);
			if(link!=null) {
			String instructorEmailAddress = link.getUserEmailAddress();
			getGoogleOwnerAccessToken(request, response, tcSessionData, instructorEmailAddress);
			}
			else {
			response.getWriter().print("ERROR");
			}
		} catch (Exception e) {
			M_log.error("Failed to Unshare info into the Setting Service",e);
			response.getWriter().print("ERROR");
		}
	}
	
	private void getGoogleOwnerAccessToken(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData,String ownerEmailAddress)
					throws IOException {
		String accessToken = GoogleSecurity.getGoogleAccessToken(
				getGoogleServiceAccount(), ownerEmailAddress);
		
		if (accessToken != null) {
			response.getWriter().print(accessToken);
		} else {
			M_log.warn("ERROR: User \""
					+ tcSessionData.getUserSourceDid()
					+ "\" got error in generating access token.  (Email: "
					+ ownerEmailAddress + "; ID: " + tcSessionData.getUserId()
					+ ")");
			response.getWriter().print("ERROR");
		}
	}
	
	

	private GoogleServiceAccount getGoogleServiceAccount() {
		return new GoogleServiceAccount(GOOGLE_SERVICE_ACCOUNT_PROPS_PREFIX);
	}

	private boolean validatePermissionsRequiredParams(
			HttpServletRequest request, HttpServletResponse response,
			TcSessionData tcSessionData) throws Exception {
		boolean result = true;
		if (getIsEmpty(tcSessionData.getUserEmailAddress())) {
			M_log.error("Error: unable to handle permissions - the request did not specify the instructor.");
			result = false;
		}
		if (getIsEmpty(request.getParameter(PARAM_ACCESS_TOKEN))) {
			M_log.error("Error: unable to handle permissions - the request did not include valid access token.");
			result = false;
		}
		if (getIsEmpty(PARAM_FILE_ID)) {
			M_log.error("Error: unable to insert permissions, as no file ID was included in the request.");
			result = false;
		}
		return result;
	}

	private GoogleCredential getGoogleCredential(HttpServletRequest request) {
		GoogleCredential result = null;
		String accessToken = request.getParameter(PARAM_ACCESS_TOKEN);
		if (!getIsEmpty(accessToken)) {
			result = new GoogleCredential().setAccessToken(accessToken);
		}
		return result;
	}

	/**
	 * Makes direct server-to-server request to get the site's roster, and
	 * returns list of users' email addresses.
	 */
	
	private HashMap<String,HashMap<String, String>> getRoster(HttpServletRequest request,
			TcSessionData tcSessionData) throws ServletException, IOException {
		return RosterClientUtils.getRosterFull(tcSessionData);
	}

	private void logErrorWritingResponse(HttpServletResponse response, String message)
			throws IOException {
		M_log.warn(message);
		response.getWriter().print(message);
	}

	/**
	 * Overload that gets JSP page to open from parameter in the request.
	 */
	private void loadJspPage(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData)
					throws ServletException, IOException {
		String pageName = request.getParameter(PARAM_OPEN_PAGE_NAME);
		loadJspPage(request, response, tcSessionData, JspPage.valueOf(pageName));
	}

	/**
	 * Forwards the request to open owner (container) JSP /view/root.jsp,
	 * loading the given JSP page as container's contents.
	 * 
	 * @param request
	 *            HttpServletRequest storing the JSP page for use by owner
	 * @param response
	 *            HttpServletResponse for forward
	 * @param tcSessionData
	 * @param jspPage
	 *            JspPage enum containing page-specific settings
	 * @throws ServletException
	 * @throws IOException
	 */
	private void loadJspPage(HttpServletRequest request,
			HttpServletResponse response, TcSessionData tcSessionData,
			JspPage jspPage) throws ServletException, IOException {
		if (jspPage.verifyAllowedRoles(tcSessionData.getUserRoleArray())) {
			request.setAttribute("jspPage", jspPage);
			retrieveGoogleDriveConfigFromSession(tcSessionData, request);

			request.setAttribute("search", resource.getString("gd.search"));
			request.setAttribute("linkingViewInfo",
					resource.getString("gd.linking.view.info"));
			request.setAttribute("createAndLinkButton",
					resource.getString("gd.create.link.button"));

			request.setAttribute("info",
					resource.getString("gd.linked.view.info"));
			request.setAttribute("studentInfo",
					resource.getString("gd.student.view.info"));
			request.setAttribute("deleteButton",
					resource.getString("gd.delete.button"));
			request.setAttribute("addButton",
					resource.getString("gd.add.button"));
			
			request.setAttribute("deleteFileErrorAlert",
					resource.getString("gd.delete.file.error.alert"));
			request.setAttribute("deleteFolderErrorAlert",
					resource.getString("gd.delete.folder.error.alert"));

			request.setAttribute("header2", resource.getString("gd.header2"));
			request.setAttribute("about",
					resource.getString("gd.header3.about"));
			request.setAttribute("help", resource.getString("gd.header4.help"));
			request.setAttribute("loggedMsg",
					resource.getString("gd.logged.in"));
			request.setAttribute("studentAccessMsg",
					resource.getString("gd.student.view.access.msg"));
			request.setAttribute("studentNoFolderAccessMsg",
					resource.getString("gd.student.view.nofolder.message"));
			request.setAttribute("invalidAccountMsg",
					resource.getString("gd.invalid.account.message"));
			request.setAttribute("permissionUpdate",
					resource.getString("gd.student.view.permission.update"));
			request.setAttribute("linkFolderButton",
					resource.getString("gd.link.folder.button"));
			request.setAttribute("unlinkFolderButton",
					resource.getString("gd.unlink.button"));
			request.setAttribute("unlinkFolderAlert",
					resource.getString("gd.unlink.folder.alert"));
			request.setAttribute("linkFolderAlert",
					resource.getString("gd.link.folder.alert"));
			request.setAttribute("errorMsg404",
							resource.getString("gd.error.msg.404"));

			request.setAttribute("deleteFilePrompt",
					resource.getString("gd.delete.file.prompt"));
			request.setAttribute("deleteFolderPrompt",
					resource.getString("gd.delete.folder.prompt"));
			request.setAttribute("deleteItemPromptHeader",
					resource.getString("gd.delete.item.prompt.header"));
			request.setAttribute("createItemPromptHeader",
					resource.getString("gd.create.item.prompt.header"));
			request.setAttribute("createItemPrompt",
					resource.getString("gd.create.item.prompt"));
			request.setAttribute("createItemPromptError",
					resource.getString("gd.create.item.prompt.error"));
			request.setAttribute("createItemAlert",
					resource.getString("gd.create.item.alert"));
			request.setAttribute("deleteItemAlert",
					resource.getString("gd.delete.item.alert"));
			request.setAttribute("linkFolderErrorAlert",
					resource.getString("gd.link.folder.error.alert"));
			request.setAttribute("sendEmailPrompt",
					resource.getString("gd.send.email.prompt"));
			request.setAttribute("sendEmailPromptHeader",
					resource.getString("gd.send.email.prompt.header"));
			request.setAttribute("buttonYes",
					resource.getString("gd.button.yes"));
			request.setAttribute("buttonNo",
					resource.getString("gd.button.no"));
			request.setAttribute("buttonCreate",
					resource.getString("gd.button.create"));
			request.setAttribute("buttonDelete",
					resource.getString("gd.button.delete"));
			request.setAttribute("contextUrl",
					getGoogleServiceAccount().getContextURL());
			

			getServletContext().getRequestDispatcher("/view/root.jsp").forward(
					request, response);
		} else {
			StringBuilder sb = new StringBuilder();
			sb.append("Unauthorized attempt to acces JSP page ");
			sb.append(jspPage);
			sb.append(" requiring roles ");
			sb.append(Arrays.toString(jspPage.getRoles()));
			sb.append(" by ");
			sb.append(tcSessionData.getUserNameFull());
			sb.append(" <");
			sb.append(tcSessionData.getUserEmailAddress());
			sb.append("> with roles ");
			sb.append(Arrays.toString(tcSessionData.getUserRoleArray()));
			M_log.warn(sb.toString());
			loadJspPage(request, response, tcSessionData, JspPage.Home);
		}
	}

	// Inner classes ------------------------------------------------

	// Talking to google with respect to permission insertion and deletion
	private class FolderPermissionsHandler {
		// Instance variables ---------------------------------------

		private TcSiteToGoogleLink link;
		private Drive drive;
		private String fileId;

		// Constructors ---------------------------------------------

		FolderPermissionsHandler(TcSiteToGoogleLink link, Drive drive,
				String fileId) {
			setLink(link);
			setDrive(drive);
			setFileId(fileId);
		}

		// Public methods -------------------------------------------

		private File getFile() throws IOException {
			return getDrive().files().get(getFileId()).execute();
		}

		public boolean getIsInstructor(String userEmailAddress) {
			return getLink().getUserEmailAddress().equals(userEmailAddress);
		}

		private Permission insertPermission(String userEmailAddress,String role,
				boolean sendNotificationEmails) {
			Permission result = null;
			try {
				Permission newPermission = new Permission();
				newPermission.setValue(userEmailAddress);
				newPermission.setType("user");
				if(role.equals("Instructor")) {
					newPermission.setRole("writer");
				}
				else {
				newPermission.setRole("reader");
				}
				result = getDrive().permissions()
						.insert(getFileId(), newPermission)
						.setSendNotificationEmails(sendNotificationEmails)
						.execute();
			} catch (Exception err) {
				StringBuilder sb = new StringBuilder();
				sb.append("Failed to insert permission for user \"");
				sb.append(userEmailAddress);
				sb.append("\" on file \"");
				sb.append(getFileId());
				sb.append("\"");
				M_log.warn(sb.toString());
				err.printStackTrace();
			}
			return result;
		}

		private boolean removePermission(String permissionId) {
			boolean result = false;
			try {
				getDrive().permissions().delete(getFileId(), permissionId)
				.execute();
				// No errors indicates this operation succeeded
				result = true;
			} catch (Exception err) {
				StringBuilder sb = new StringBuilder();
				sb.append("Failed to remove permission ");
				sb.append(permissionId);
				sb.append(" for user \"");
				sb.append(getLink().getUserEmailAddress());
				sb.append("\" on file \"");
				sb.append(getFileId());
				sb.append("\"");
				M_log.warn(sb.toString());
				err.printStackTrace();
			}
			return result;
		}

		// Public accessory methods ---------------------------------

		public TcSiteToGoogleLink getLink() {
			return link;
		}

		public void setLink(TcSiteToGoogleLink value) {
			link = value;
		}

		public Drive getDrive() {
			return drive;
		}

		public void setDrive(Drive value) {
			drive = value;
		}

		public String getFileId() {
			return fileId;
		}

		public void setFileId(String value) {
			fileId = value;
		}
	}
}
