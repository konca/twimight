package ch.ethz.twimight.net.tds;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.http.HttpVersion;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnManagerPNames;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.json.JSONException;

import ch.ethz.twimight.activities.LoginActivity;
import ch.ethz.twimight.data.FriendsKeysDBHelper;
import ch.ethz.twimight.data.LocationDBHelper;
import ch.ethz.twimight.data.MacsDBHelper;
import ch.ethz.twimight.data.RevocationDBHelper;
import ch.ethz.twimight.security.CertificateManager;
import ch.ethz.twimight.security.KeyManager;
import ch.ethz.twimight.security.RevocationListEntry;
import ch.ethz.twimight.util.Constants;
import ch.ethz.twimight.util.EasySSLSocketFactory;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * The service to send all kinds of API calls to the disaster server. 
 * @author thossman
 *
 */
public class TDSService extends Service {

	private static final String TAG = "TDSService";


	private static final String TDS_LAST_UPDATE = "TDSLastUpdate"; /** Name of the last update in shared preferences */
	private static final String TDS_UPDATE_INTERVAL = "TDSUpdateInterval"; /** Name of the update interval in shared preference */

	public static final int SYNCH_ALL = 1;
	public static final int SYNCH_REVOKE = 2;
	public static final int SYNCH_SIGN = 3;
	public static final int SYNCH_ALL_FORCE = 4;

	TDSCommunication tds;

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	/**
	 * Executed when the service is started. We return START_STICKY to not be stopped immediately.
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId){
		Log.d(TAG, "started..");
		
		//  release the lock
		TDSAlarm.releaseWakeLock();
		
		// Do we have connectivity?
		ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		if(cm.getActiveNetworkInfo()==null || !cm.getActiveNetworkInfo().isConnected()){
			Log.d(TAG, "Error synching: no connectivity");
			schedulePeriodic(false);
			return START_NOT_STICKY;
			
		} else {
			// Create twitter object
			String token = LoginActivity.getAccessToken(this);
			String secret = LoginActivity.getAccessTokenSecret(this);
			if(!(token == null || secret == null) ) {
				try {
					tds = new TDSCommunication(getBaseContext(), Constants.CONSUMER_ID, token, secret);
				} catch (JSONException e) {
					Log.e(TAG, "error while setting up TDS Communication");
					schedulePeriodic(false);
					return START_NOT_STICKY;
				}
			} else {
				Log.i(TAG, "Error synching: no access token or secret");
				schedulePeriodic(false);
				
				return START_NOT_STICKY;
			}


			// check what we have to synch
			int synchRequest = intent.getIntExtra("synch_request", SYNCH_ALL);
			switch(synchRequest){
			case SYNCH_ALL:				
				synchAll();
				break;
				
			case SYNCH_ALL_FORCE:				
				synchAllForce();
				break;
				
			case SYNCH_REVOKE:				
				synchRevoke();
				break;
				
			case SYNCH_SIGN:				
				synchSign();
				break;
				
			default:
				throw new IllegalArgumentException("Exception: Unknown synch request");
			}

			return START_NOT_STICKY;
		}

		
	}

	/**
	 * Regular TDS update, if needed
	 */
	private void synchAll() {
		
		if(needUpdate()){
			Log.d(TAG, "starting synch task");
			new SynchAllTask().execute();
		} else {
			TDSAlarm.scheduleCommunication(this, Constants.TDS_UPDATE_INTERVAL - (System.currentTimeMillis() - getLastUpdate(getBaseContext())));
			Log.d(TAG, "no synch needed");
		}
	}
	
	/**
	 * Regular TDS update, forced (outside the update schedule)
	 */
	private void synchAllForce() {
		Log.i(TAG, "starting synch task");
		new SynchAllTask().execute();

	}

	/**
	 * Revoke the current key
	 */
	private void synchRevoke(){
		new RevokeTask().execute();
	}

	/**
	 * Sign a new key
	 */
	private void synchSign(){
		new SignTask().execute();
	}
	
	/**
	 * Schedule the next periodic TDS communication
	 */
	private void schedulePeriodic(boolean result){
		if(result){
			// remember the time of successful update
			setLastUpdate();

			// reset retry interval
			setUpdateInterval(Constants.TDS_UPDATE_RETRY_INTERVAL);

			// reschedule
			TDSAlarm.scheduleCommunication(getBaseContext(), Constants.TDS_UPDATE_INTERVAL);

			Log.i(TAG,"update successful");
		} else {
			// get the last successful update
			Long lastUpdate = getLastUpdate(getBaseContext());

			// get from shared preferences
			Long currentUpdateInterval = getUpdateInterval();

			// when should the next update be scheduled?
			Long nextUpdate = 0L;
			if(System.currentTimeMillis()-lastUpdate > Constants.TDS_UPDATE_INTERVAL){
				nextUpdate = currentUpdateInterval;
			} else {
				nextUpdate = Constants.TDS_UPDATE_INTERVAL - (System.currentTimeMillis()-lastUpdate);
			}

			// exponentially schedule again after TDS_UPDAT_RETRY_INTERVAL
			TDSAlarm.scheduleCommunication(getBaseContext(), nextUpdate);

			currentUpdateInterval *= 2;
			// cap at TDS_UPDATE_INTERVAL
			if(currentUpdateInterval > Constants.TDS_UPDATE_INTERVAL){
				currentUpdateInterval = Constants.TDS_UPDATE_INTERVAL;
			}

			// write back to shared preferences
			setUpdateInterval(currentUpdateInterval);
			Log.i(TAG, "update not successful");
		}

		
	}

	/**
	 * Get the time (unix time stamp) of the last successful update
	 */
	public static Long getLastUpdate(Context context){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		return prefs.getLong(TDS_LAST_UPDATE, 0);

	}

	/**
	 * Set the current time (unix time stamp) as the time of last successful update
	 */
	private void setLastUpdate() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putLong(TDS_LAST_UPDATE, System.currentTimeMillis());
		prefEditor.commit();
	}

	/**
	 * Get the current update interval from Shared preferences
	 */
	private Long getUpdateInterval() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		return prefs.getLong(TDS_UPDATE_INTERVAL, Constants.TDS_UPDATE_RETRY_INTERVAL);

	}

	/**
	 * Stores the current update interval in Shared preferences 
	 */
	private void setUpdateInterval(Long updateInterval) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putLong(TDS_UPDATE_INTERVAL, updateInterval);
		prefEditor.commit();

	}

	/**
	 * Do we need a periodic update?
	 */
	private boolean needUpdate(){

		// when was the last successful update?
		if(System.currentTimeMillis() - getLastUpdate(getBaseContext()) > Constants.TDS_UPDATE_INTERVAL){
			return true;
		} else {
			return false;
		}
	}

	public static void resetLastUpdate(Context context){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putLong(TDS_LAST_UPDATE, 0L);
		prefEditor.commit();
	}

	public static void resetUpdateInterval(Context context){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		SharedPreferences.Editor prefEditor = prefs.edit();
		prefEditor.putLong(TDS_UPDATE_INTERVAL, Constants.TDS_UPDATE_INTERVAL);
		prefEditor.commit();		
	}

	/**
	 * Set up an HTTP client.
	 * @return
	 * @throws GeneralSecurityException
	 */
	private DefaultHttpClient getClient() throws GeneralSecurityException {

		SchemeRegistry schemeRegistry = new SchemeRegistry();
		schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
		schemeRegistry.register(new Scheme("https", new EasySSLSocketFactory(), 443));

		HttpParams params = new BasicHttpParams();
		params.setParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 30);
		params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, new ConnPerRouteBean(30));
		params.setParameter(HttpProtocolParams.USE_EXPECT_CONTINUE, false);

		HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
		HttpConnectionParams.setConnectionTimeout(params, Constants.HTTP_CONNECTION_TIMEOUT); // Connection timeout
		HttpConnectionParams.setSoTimeout(params, Constants.HTTP_SOCKET_TIMEOUT); // Socket timeout


		ClientConnectionManager cm = new SingleClientConnManager(params, schemeRegistry);
		return new DefaultHttpClient(cm, params);

	}

	/**
	 * This Task performs the periodic communication with the TDS
	 * @author thossmann
	 *
	 */
	private class SynchAllTask extends AsyncTask<Void, Void, Boolean>{

		/**
		 * The task
		 */
		@Override
		protected Boolean doInBackground(Void... params) {
			try{
				// wait a bit until we have connectivity
				// TODO: This is a hack, we should wait for a connectivity change intent, or a timeout, to proceed.
				Thread.sleep(Constants.WAIT_FOR_CONNECTIVITY);

				// push locations to the server
				LocationDBHelper locationAdapter = new LocationDBHelper(getBaseContext());
				locationAdapter.open();

				Date sinceWhen = new Date(getLastUpdate(getBaseContext()));
				ArrayList<Location> locationList = (ArrayList<Location>) locationAdapter.getLocationsSince(sinceWhen);
				if(!locationList.isEmpty()){
					tds.createLocationObject(locationList);
				}

				// request potential bluetooth peers
				String mac = PreferenceManager.getDefaultSharedPreferences(getBaseContext()).getString("mac", null);
				if(mac!=null){
					tds.createBluetoothObject(mac);
				}

				// revocation list
				RevocationDBHelper rm = new RevocationDBHelper(getBaseContext());
				rm.open();
				tds.createRevocationObject(rm.getCurrentVersion());

				// do we need a new certificate?
				CertificateManager cm = new CertificateManager(getBaseContext());
				if(cm.needNewCertificate()){
					KeyManager km = new KeyManager(getBaseContext());
					tds.createCertificateObject(km.getKey(), null);
					Log.i(TAG, "we need a new certificate");
				} 
				
				// follower key list
				FriendsKeysDBHelper fm = new FriendsKeysDBHelper(getBaseContext());
				tds.createFollowerObject(fm.getLastUpdate());

			} catch(Exception e) {
				Log.e(TAG, "Exception while assembling request");
				return false;
			}

			boolean success = false;
			// Send the request
			try {
				success = tds.sendRequest(getClient());
			} catch (GeneralSecurityException e) {
				Log.e(TAG, "GeneralSecurityException while sending TDS request");
			}

			if(!success) 				
				return false;			

			Log.i(TAG, "success");
			try {

				// authentication
				String twitterId = tds.parseAuthentication();
				if(!twitterId.equals(LoginActivity.getTwitterId(getBaseContext()))){
					Log.e(TAG, "Twitter ID mismatch!");
					return false;
				}
				

				// bluetooth
				List<String> macsList = tds.parseBluetooth();
				//TODO: 
				///////////////////////////////////////////////////////////////////////
				macsList.clear();
				///////////////////////////////////////////////////////////////////////
				if(!macsList.isEmpty()){
					MacsDBHelper dbHelper = new MacsDBHelper(getBaseContext());
					dbHelper.open();

					// temporarily de-activate all local MACs
					dbHelper.updateMacsDeActive();

					// insert new macs in the DB
					Iterator<String> iterator = macsList.iterator();
					while(iterator.hasNext()) {

						String mac = iterator.next();
						if(dbHelper.createMac(mac, 1) == -1){
							dbHelper.updateMacActive(mac, 1);
							Log.d(TAG, "Already have MAC: " + mac);
						} else {
							Log.d(TAG,"New MAC: " + mac);
						}
					}
				} else {
					Log.d(TAG, "bluetooth mac list empty");
				}
				
				// location, nothing to do here

				// certificate
				String certPem = tds.parseCertificate();
				if(certPem != null){
					CertificateManager cm = new CertificateManager(getBaseContext());
					cm.setCertificate(certPem);
				}
				Log.d(TAG, "certificate parsed");

				// revocation
				RevocationDBHelper rm = new RevocationDBHelper(getBaseContext());
				rm.open();
				rm.deleteExpired();
				// first, we check the version
				int revocationListVersion = tds.parseRevocationVersion();
				if(revocationListVersion!=0 && revocationListVersion > rm.getCurrentVersion()){
					// next, we get the update of the list
					List<RevocationListEntry> revocationList = tds.parseRevocation();
					if(!revocationList.isEmpty()){
						rm.processUpdate(revocationList);
					}
					rm.setCurrentVersion(revocationListVersion);
					// check if our certificate is on the new revocation list
					CertificateManager cm = new CertificateManager(getBaseContext());
					if(rm.isRevoked(cm.getSerial())){
						Log.i(TAG, "Our certificate got revoked! Deleting key and certificate");
						cm.deleteCertificate();
						KeyManager km = new KeyManager(getBaseContext());
						km.deleteKey();
					}
				} else {
					Log.d(TAG, "no new revocations");
				}
				Log.d(TAG, "revocation parsed");

				// Followers
				FriendsKeysDBHelper fm = new FriendsKeysDBHelper(getBaseContext());				
				fm.open();
				List<TDSPublicKey> keyList = tds.parseFollower();			
				if(keyList != null){
					fm.insertKeys(keyList);
					long lastUpdate = tds.parseFollowerLastUpdate();
					if(lastUpdate != 0){
						fm.setLastUpdate(lastUpdate);
					}
				}
				Log.i(TAG, "followers parsed");

			} catch(Exception e) {
				Log.e(TAG, "Exception while parsing response",e);
			}

			return true;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			schedulePeriodic(result);
		}

	}

	/**
	 * This Task revokes the current certificate.
	 * @author thossmann
	 *
	 */
	private class RevokeTask extends AsyncTask<Void, Void, Boolean>{

		/**
		 * The task
		 */
		@Override
		protected Boolean doInBackground(Void... params) {
			try{
				KeyManager km = new KeyManager(getBaseContext());			
				tds.createCertificateObject(null, km.getKey());
			} catch(Exception e) {
				Log.e(TAG, "Exception while assembling request");
				return false;
			}

			boolean success = false;
			// Send the request
			try {
				success = tds.sendRequest(getClient());
			} catch (GeneralSecurityException e) {
				Log.e(TAG, "GeneralSecurityException while sending TDS request");
			}

			if(!success) {
				Log.e(TAG, "Error while sending");
				return false;
			}

			Log.i(TAG, "success");

			try {

				// authentication
				String twitterId = tds.parseAuthentication();
				if(!twitterId.equals(LoginActivity.getTwitterId(getBaseContext()))){
					Log.e(TAG, "Twitter ID mismatch!");
					return false;
				}
				Log.i(TAG, "authentication parsed");


				// certificate
				int status = tds.parseCertificateStatus();
				if(status != 200) {
					return false;
				} else {
					// delete certificate
					CertificateManager cm = new CertificateManager(getBaseContext());
					cm.deleteCertificate();
					// delete key
					KeyManager km = new KeyManager(getBaseContext());
					km.deleteKey();
				}
				Log.i(TAG, "certificate parsed");

			} catch(Exception e) {
				Log.e(TAG, "Exception while parsing response");
				return false;
			}

			return true;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if(result){
				// create new key
				KeyManager km = new KeyManager(getBaseContext());
				km.generateKey();
				// send the key for signing
				new SignTask().execute();

				// TODO: How can we notify the user here??
			} else {
				// TODO: How can we notify the user here??
			}
		}

	}

	/**
	 * This Task submits the kurrent key for signing
	 * @author thossmann
	 *
	 */
	private class SignTask extends AsyncTask<Void, Void, Boolean>{

		/**
		 * The task
		 */
		@Override
		protected Boolean doInBackground(Void... params) {
			try{
				KeyManager km = new KeyManager(getBaseContext());			
				tds.createCertificateObject(km.getKey(), null);
			} catch(Exception e) {
				Log.e(TAG, "Exception while assembling request");
				return false;
			}

			boolean success = false;
			// Send the request
			try {
				success = tds.sendRequest(getClient());
			} catch (GeneralSecurityException e) {
				Log.e(TAG, "GeneralSecurityException while sending TDS request");
			}

			if(!success) {
				Log.e(TAG, "Error while sending");
				return false;
			}

			Log.i(TAG, "success");

			try {

				// authentication
				String twitterId = tds.parseAuthentication();
				if(!twitterId.equals(LoginActivity.getTwitterId(getBaseContext()))){
					Log.e(TAG, "Twitter ID mismatch!");
					return false;
				}
				Log.i(TAG, "authentication parsed");


				// certificate
				int status = tds.parseCertificateStatus();
				if(status != 200) {
					return false;
				} else {
					// insert new certificate
					CertificateManager cm = new CertificateManager(getBaseContext());
					String certificatePem = tds.parseCertificate();
					cm.setCertificate(certificatePem);
				}
				Log.i(TAG, "certificate parsed");

			} catch(Exception e) {
				Log.e(TAG, "Exception while parsing response");
				return false;
			}

			return true;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if(result){
				// TODO: how do we notify the user?

			} else {
				// TODO: how do we notify the user?
			}
		}

	}

}
