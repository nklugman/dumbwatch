package edu.umich.eecs.gridwatch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.MediaRecorder.OnInfoListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings.Secure;
import android.util.Log;
import android.widget.Toast;

public class GridWatchService extends Service implements SensorEventListener {

	// Constants for transmitting data to the main thred
	private final static String INTENT_NAME = "GridWatch-update-event";
	private final static String INTENT_EXTRA_EVENT_TYPE = "event_type";
	private final static String INTENT_EXTRA_EVENT_INFO = "event_info";
	private final static String INTENT_EXTRA_EVENT_TIME = "event_time";

	private final static String INTENT_EXTRA_EVENT_MANUAL_ON = "event_manual_on";
	private final static String INTENT_EXTRA_EVENT_MANUAL_OFF = "event_manual_off";
	private final static String INTENT_EXTRA_EVENT_MANUAL_WD = "event_manual_wd";
	private final static String INTENT_MANUAL_KEY = "manual_state";
	
	// How long to wait before forcing the phone to update locations.
	// This is not set to immediate in case another app does the update
	// first and we can just use that.
	private final static long LOCATION_WAIT_TIME = 300000l;

	// How long to wait between checks of the event list
	// for events that are finished and can be sent to the
	// server.
	private final static int EVENT_PROCESS_TIMER_PERIOD = 1000;

	// Audio recording

	//private final static int TIME_MS = 3000;
	//private static MediaRecorder mRecorder = null;
	
	private AudioRecord mRecorder = null;
	private final static int SAMPLE_FREQUENCY = 44100;
	private final static int BIT_RATE = 16;
	private final static int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_STEREO;
	private final static int RECORDER_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
	private final static int RECORDER_TIME = 3000;
	
	private static String recordingFileName = null;
	private final static String recordingFileTmpName = "gw_tmp.raw";
	private final static String recordingFolder = "GW_recordings";
	private final static String recordingExtension = ".wav"; 
	
	// Debug Tags
	private static String errorTag = "error";
	private static String noteTag = "note";
	
	// List of all of the active events we are currently handling
	private ArrayList<GridWatchEvent> mEvents = new ArrayList<GridWatchEvent>();

	// State for the accelerometer
	private SensorManager mSensorManager;
	private Sensor mAccel;

	// Tool to get the location
	private LocationManager mLocationManager;

	// Timer that is fired to check if each event is ready to be sent to
	// the server.
	private Timer mEventProcessTimer = new Timer();

	// Array of messages ready to send to the server that are waiting for
	// Internet connectivity.
	private LinkedBlockingQueue<HttpPost> mAlertQ = new LinkedBlockingQueue<HttpPost>();

	// Tool for getting a pretty date
	private DateFormat mDateFormat = DateFormat.getDateTimeInstance();

	// Object that handles writing and retrieving log messages
	private GridWatchLogger mGWLogger;

	// Object that handles writing and retrieving a 
	private GridWatchID mGWID;

	@Override
	public void onCreate() {
		
		mGWLogger = new GridWatchLogger();
		mGWLogger.log(mDateFormat.format(new Date()), "created", null);
		
		// Receive a callback when Internet connectivity is restored
		IntentFilter cfilter = new IntentFilter();
		cfilter.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
		this.registerReceiver(mConnectionListenerReceiver, cfilter);

		// Receive callbacks when the power state changes (plugged in, etc.)
		IntentFilter ifilter = new IntentFilter();
		ifilter.addAction(Intent.ACTION_POWER_CONNECTED);
		ifilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
		ifilter.addAction(Intent.ACTION_DOCK_EVENT);
		ifilter.addAction(INTENT_EXTRA_EVENT_MANUAL_OFF);
		ifilter.addAction(INTENT_EXTRA_EVENT_MANUAL_ON);
		ifilter.addAction(INTENT_EXTRA_EVENT_MANUAL_WD);
		this.registerReceiver(mPowerActionReceiver, ifilter);

		// Get references to the accelerometer api
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.GINGERBREAD) {
			mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
			mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		}

		// Get a reference to the location manager
		mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

		Toast.makeText(this, "GridWatch started", Toast.LENGTH_SHORT).show();
		
	}

	@Override
	public void onDestroy() {
		mGWLogger.log(mDateFormat.format(new Date()), "destroyed", null);


		Log.d("GridWatchService", "service destroyed");
		Toast.makeText(this, "GridWatch ended", Toast.LENGTH_SHORT).show();

		// Unregister us from different events
		this.unregisterReceiver(mPowerActionReceiver);
		this.unregisterReceiver(mConnectionListenerReceiver);
	}

	// This is the old onStart method that will be called on the pre-2.0
	// platform.  On 2.0 or later we override onStartCommand() so this
	// method will not be called.
	@Override
	public void onStart(Intent intent, int startId) {
		mGWLogger.log(mDateFormat.format(new Date()), "started_old", null);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		mGWLogger.log(mDateFormat.format(new Date()), "started", null);


		if (intent != null && intent.getExtras() != null) {
			if (intent.getExtras().getString(INTENT_MANUAL_KEY).equals(INTENT_EXTRA_EVENT_MANUAL_ON)) {
				Log.w(noteTag, "power connected");
				onPowerConnected();
			}
			else if (intent.getExtras().getString(INTENT_MANUAL_KEY).equals(INTENT_EXTRA_EVENT_MANUAL_OFF)) {
				Log.w(noteTag, "power disconnected");
				onPowerDisconnected();
			} 
			else if (intent.getExtras().getString(INTENT_MANUAL_KEY).equals(INTENT_EXTRA_EVENT_MANUAL_WD)) {
				Log.w(noteTag, "power WD");
				onWD();
			} else {
				Log.w(errorTag, "Unknown intent: " + intent.getAction());
			}
		}
		
		// We want this service to continue running until it is explicitly
		// stopped, so return sticky.
		return START_STICKY;
	}

	

	// Handles the call back for when various power actions occur
	private BroadcastReceiver mPowerActionReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_POWER_CONNECTED)) {
				onPowerConnected();
			} else if (intent.getAction().equals(Intent.ACTION_POWER_DISCONNECTED)) {
				onPowerDisconnected();
			} else if (intent.getAction().equals(Intent.ACTION_DOCK_EVENT)) {
				onDockEvent(intent);
			} else {
				Log.d("GridWatchService", "Unknown intent: " + intent.getAction());
			}
		}
	};

	// Handles the call when Internet connectivity is restored
	private BroadcastReceiver mConnectionListenerReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			ConnectivityManager cm = ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE));
				if (cm == null) {
					return;
				}
				// If we have regained Internet connectivity, process any backlog of alerts
				// we need to send.
				if (cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected()) {
					new ProcessAlertQTask().execute();
				}
		}
	};

	// Call to update the UI thread with data from this service
	private void broadcastIntent (Intent lIntent) {
		//LocalBroadcastManager.getInstance(this).sendBroadcast(lIntent)
		lIntent.setPackage("edu.umich.eecs.gridwatch");
		sendBroadcast(lIntent);
	}

	private void onPowerConnected() {

		// Take the opportunity to try to update our location. Since we now have
		// power (the device was just plugged in), getting a GPS lock shouldn't
		// be an issue. Also, since the phone won't move between now and when
		// it is unplugged (given how power cables work) the location should
		// be valid when the device is unplugged.
		updateLocation();

		// Create the plug event
		GridWatchEvent gwevent = new GridWatchEvent(GridWatchEventType.PLUGGED);
		mEvents.add(gwevent);

		// This one we don't need any sensors so go ahead and process the event
		// list because we can send the plugged event.
		
		processEvents();
	}
	
	private void onWD() {
		updateLocation();

		// Create the plug event
		GridWatchEvent gwevent = new GridWatchEvent(GridWatchEventType.WD);
		mEvents.add(gwevent);

		// This one we don't need any sensors so go ahead and process the event
		// list because we can send the plugged event.
		processEvents();
		
	}

	private void onPowerDisconnected() {

		Log.w(noteTag, "onPowerDisconnected");
		
		// Create the unplugged event
		GridWatchEvent gwevent = new GridWatchEvent(GridWatchEventType.UNPLUGGED);
		mEvents.add(gwevent);
		
		// Start the accelerometer getting samples
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.GINGERBREAD) {
			mSensorManager.registerListener(this, mAccel, SensorManager.SENSOR_DELAY_NORMAL);
		}
		
		// Sample the microphone TODO
		int currentapiVersion = android.os.Build.VERSION.SDK_INT;
		if (currentapiVersion >= android.os.Build.VERSION_CODES.GINGERBREAD){ // Disable sensors on old API
			Thread audioThread = new Thread(new GridWatchEventThread(gwevent));
			audioThread.start();
		}
		
		// Make sure the event queue is processed until it is empty
		startEventProcessTimer();
	}

	private void onDockEvent(Intent intent) {
		int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, -1);
		boolean dockCar = dockState == Intent.EXTRA_DOCK_STATE_CAR;
		Log.d("GridWatchService", "mDockCar set to " + dockCar);
	}

	// Iterate over the list of pending events and determine if any
	// should be transmitted to the server
	private void processEvents () {
		boolean done = true;
			
		/*
		for(Iterator<GridWatchEvent> gwevent = mEvents.iterator(); gwevent.hasNext();){
			if (((GridWatchEvent) gwevent).readyForTransmission()) {
				postEvent((GridWatchEvent) gwevent);
				mEvents.remove(gwevent);
			} else {
				done = false;
			}
		} 
		*/
		
		for (int i = 0; i < mEvents.size(); i++) {
			GridWatchEvent gwevent = mEvents.get(i);
			if (gwevent.readyForTransmission()) {
				postEvent(gwevent);
				mEvents.remove(gwevent);
			} else {
				done = false;
			}
		}
		
		/*
		for (GridWatchEvent gwevent : mEvents) {
			if (gwevent.readyForTransmission()) {
				postEvent(gwevent);
				mEvents.remove(gwevent);
			} else {
				done = false;
			}
		}
		*/
		
		if (!done) {
			// If there are still events in the queue make sure the
			// timer fires again.
			startEventProcessTimer();
		}
	}

	// Create a timer to check the events queue when it fires
	private void startEventProcessTimer () {
		mEventProcessTimer.schedule(new TimerTask () {
			@Override
			public void run () {
				processEvents();
			}
		}, EVENT_PROCESS_TIMER_PERIOD);
	}

	// This is called when new samples arrive from the accelerometer
	@Override
	public final void onSensorChanged(SensorEvent event) {
		
		// TODO this should never be false... cut of the sensor early at the top. Hack
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.GINGERBREAD) {
			boolean done = true; // assume we are done until proven otherwise

			
			/*
			// Loop through all of our active events and pass them accelerometer data
			for (GridWatchEvent gwevent : mEvents) {
				boolean gwevent_done = gwevent.addAccelerometerSample(event.timestamp,
						event.values[0], event.values[1], event.values[2]);

				if (!gwevent_done) {
					done = false;
				}
			}
			*/
			
			for (int i = 0; i < mEvents.size(); i++) {
				GridWatchEvent gwevent = mEvents.get(i);
				boolean gwevent_done = gwevent.addAccelerometerSample(event.timestamp,
						event.values[0], event.values[1], event.values[2]);

				if (!gwevent_done) {
					done = false;
				}
			}
			
			
			/*
			for(Iterator<GridWatchEvent> gwevent = mEvents.iterator(); gwevent.hasNext();){
				GridWatchEvent cur = (GridWatchEvent) gwevent;
				boolean gwevent_done = cur.addAccelerometerSample(event.timestamp,
						event.values[0], event.values[1], event.values[2]);
				if (!gwevent_done) {
					done = false;
				}
			} 
			*/

			if (done) {
				// All events are finished getting accelerometer samples, so go
				// ahead and stop this listener
				mSensorManager.unregisterListener(this);
			}
		}

	}

	// This thread handles getting audio data from the microphone
	class GridWatchEventThread implements Runnable {
		GridWatchEvent mThisEvent;

		public GridWatchEventThread (GridWatchEvent gwevent) {
			mThisEvent = gwevent;
		}

		@Override
		public void run() {

			// TODO should never be false... cut this off earlier. Hack
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.GINGERBREAD) {
				int recBufferSize = AudioRecord.getMinBufferSize(SAMPLE_FREQUENCY,
						RECORDER_CHANNELS,
						RECORDER_ENCODING);

				mRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 
						SAMPLE_FREQUENCY,
						RECORDER_CHANNELS,
						RECORDER_ENCODING, 
						recBufferSize*2);

				// Set up the tmp file before WAV conversation
				String tmpFilePath = Environment.getExternalStorageDirectory().getPath();
				File fileFolder = new File(tmpFilePath, recordingFolder);
				if (!fileFolder.exists()) fileFolder.mkdirs();
				File tmpFile = new File(tmpFilePath, recordingFileTmpName);
				if (!tmpFile.exists()) tmpFile.delete();
				String tmpFileName = fileFolder.getAbsolutePath() + "/" + recordingFileTmpName;

				byte tmpData[] = new byte[recBufferSize];
				FileOutputStream os = null;
				try {
					os = new FileOutputStream(tmpFileName);
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}

				// Get that recording going
				if (mRecorder != null) {
					mRecorder.startRecording();
					Log.w(noteTag, "Starting Recording");
				}

				// Take RECORDER_TIME worth of data
				int read = 0;
				if (os != null) {
					if (AudioRecord.ERROR_INVALID_OPERATION != read) {
						long t = System.currentTimeMillis();
						while (System.currentTimeMillis() - t <= RECORDER_TIME) {
							read = mRecorder.read(tmpData, 0, recBufferSize);
							try {
								os.write(tmpData);
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				}
				Log.w(noteTag, "Done Recording");

				// Stop recording
				mRecorder.stop();
				mRecorder.release();

				// Make a WAV file
				recordingFileName = fileFolder.getAbsolutePath() + "/" + System.currentTimeMillis() + recordingExtension;	

				// Convert RAW to WAV
				FileInputStream in = null;
				FileOutputStream out = null;
				long totalAudioLen = 0;
				long totalDataLen = totalAudioLen + 36;
				long longSampleRate = SAMPLE_FREQUENCY;
				int channels = 2;
				long byteRate = BIT_RATE * SAMPLE_FREQUENCY * channels/8;

				byte[] wavData = new byte[recBufferSize];

				try {
					in = new FileInputStream(tmpFileName);
					out = new FileOutputStream(recordingFileName);
					totalAudioLen = in.getChannel().size();
					totalDataLen = totalAudioLen + 36;

					WriteWavHeader(out, totalAudioLen, totalDataLen,
							longSampleRate, channels, byteRate);

					while(in.read(wavData) != -1){
						out.write(wavData);
					}
					in.close();
					out.close();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
				Log.w(noteTag, "Done Transfering TMP to WAV");

				// Delete the tmp file
				if (tmpFile.exists()) tmpFile.delete();
			}
		}
	
	}
	
	private void WriteWavHeader(
			FileOutputStream out, long totalAudioLen,
			long totalDataLen, long longSampleRate, int channels,
			long byteRate) throws IOException {
		
		byte[] header = new byte[44];
		
        header[0] = 'R';  // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';  // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;  // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;  // format = 1
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (2 * 16 / 8);  // block align
        header[33] = 0;
        header[34] = BIT_RATE;  // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
		
	}
	
	
	// Class that handles transmitting information about events. This
	// operates asynchronously at some point in the future.
	private class PostAlertTask extends AsyncTask<HttpPost, Void, Void> {
		
		// This gets called by the OS
		@Override
		protected Void doInBackground(HttpPost... httpposts) {
			Log.w("GridWatchService", "PostAlertTask start");

			HttpClient httpclient = new DefaultHttpClient();

			try {
				// Execute the HTTP POST request
				@SuppressWarnings("unused")
				HttpResponse response = httpclient.execute(httpposts[0]);
				//Log.d("GridWatchService", "POST response: " + response);

			} catch (ClientProtocolException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// Handle when the POST fails
				Log.d("GridWatchService", "IO Exception, queuing for later delivery");
				if (mAlertQ.offer(httpposts[0]) == false) {
					Log.e("GridWatchService", "Failed to add element to alertQ?");
				}
			}

			return null;
		}
	}

	// This class handles iterating through a backlog of messages to send
	// once Internet connectivity has been restored.
	private class ProcessAlertQTask extends AsyncTask<Void, Void, Void> {

		@Override
		protected Void doInBackground(Void... arg0) {
			Log.d("GridWatchService", "ProcessAlertQTask Start");

			HttpClient httpclient = new DefaultHttpClient();
			HttpPost post = null;

			try {
				while (mAlertQ.size() > 0) {
					post = mAlertQ.poll();
					if (post == null) {
						break;
					}
					@SuppressWarnings("unused")
					HttpResponse response = httpclient.execute(post);
					//Log.d("GridWatchService", "POST response: " + response);
				}
			} catch (ClientProtocolException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				//e.printStackTrace();
				Log.d("GridWatchService", "IO Exception, queuing for later delivery");
				if (post == null) {
					Log.w("GridWatchService", "Caught post is null?");
				} else if (mAlertQ.offer(post) == false) {
					// Worth noting the lack of offerFirst will put elements in
					// the alertQ out of order w.r.t. when they first fired, but
					// the server will re-order based on timestamp anyway
					Log.e("GridWatchService", "Failed to add element to alertQ?");
				}
			}

			return null;
		}
	}

	// Function to call to notify the server than an event happened on this phone.
	private void postEvent (GridWatchEvent gwevent) {
		
		Log.w(noteTag, "postEvent Hit");
		
		List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(15);
		List<NameValuePair> dumbPairs = new ArrayList<NameValuePair>(4);


		// Get the url of the server to post to
		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
		String alertServerURL = settings.getString("alert_server", getString(R.string.default_alert_server));

		// Get basics from the event
		nameValuePairs.add(new BasicNameValuePair("time", String.valueOf(gwevent.getTimestampMilli())));
		nameValuePairs.add(new BasicNameValuePair("event_type", gwevent.getEventType()));
		
		dumbPairs.add(new BasicNameValuePair("t", String.valueOf(gwevent.getTimestampMilli())));
		dumbPairs.add(new BasicNameValuePair("e", gwevent.getEventType().substring(0, 1)));

		// Get the phone's current location
		Location gpsLocation = getLocationByProvider(LocationManager.GPS_PROVIDER);
		if (gpsLocation != null) {
			nameValuePairs.add(new BasicNameValuePair("gps_latitude", String.valueOf(gpsLocation.getLatitude())));
			nameValuePairs.add(new BasicNameValuePair("gps_longitude", String.valueOf(gpsLocation.getLongitude())));
			nameValuePairs.add(new BasicNameValuePair("gps_accuracy", String.valueOf(gpsLocation.getAccuracy())));
			nameValuePairs.add(new BasicNameValuePair("gps_time", String.valueOf(gpsLocation.getTime())));
			nameValuePairs.add(new BasicNameValuePair("gps_altitude", String.valueOf(gpsLocation.getAltitude())));
			nameValuePairs.add(new BasicNameValuePair("gps_speed", String.valueOf(gpsLocation.getSpeed())));
			
			dumbPairs.add(new BasicNameValuePair("l", String.valueOf(gpsLocation.getLatitude())));
			dumbPairs.add(new BasicNameValuePair("n", String.valueOf(gpsLocation.getLongitude())));
			dumbPairs.add(new BasicNameValuePair("a", String.valueOf(gpsLocation.getAccuracy())));
			dumbPairs.add(new BasicNameValuePair("g", String.valueOf(gpsLocation.getTime())));
		}
		Location networkLocation = getLocationByProvider(LocationManager.NETWORK_PROVIDER);
		if (networkLocation != null) {
			nameValuePairs.add(new BasicNameValuePair("network_latitude", String.valueOf(networkLocation.getLatitude())));
			nameValuePairs.add(new BasicNameValuePair("network_longitude", String.valueOf(networkLocation.getLongitude())));
			nameValuePairs.add(new BasicNameValuePair("network_accuracy", String.valueOf(networkLocation.getAccuracy())));
			nameValuePairs.add(new BasicNameValuePair("network_time", String.valueOf(networkLocation.getTime())));
			nameValuePairs.add(new BasicNameValuePair("network_altitude", String.valueOf(networkLocation.getAltitude())));
			nameValuePairs.add(new BasicNameValuePair("network_speed", String.valueOf(networkLocation.getSpeed())));
		}

		// Determine if we are on wifi, mobile, or have no connection
		String connection_type = "unknown";
		ConnectivityManager cm = (ConnectivityManager) getBaseContext().getSystemService(Context.CONNECTIVITY_SERVICE);
		if (cm != null) {
			NetworkInfo active_net_info = cm.getActiveNetworkInfo();
			if (active_net_info != null) {
				if (active_net_info.isConnected()) {
					if (active_net_info.getType() == ConnectivityManager.TYPE_WIFI) {
						connection_type = "wifi";
					} else if (active_net_info.getType() == ConnectivityManager.TYPE_MOBILE) {
						connection_type = "mobile";
					} else {
						connection_type = "other";
					}
				} else {
					connection_type = "disconnected";
				}
			}
		}
		nameValuePairs.add(new BasicNameValuePair("network", connection_type));
		dumbPairs.add(new BasicNameValuePair("c", connection_type.substring(0, 1)));
		
		// Add any other key value pairs that the event needs to append
		nameValuePairs.addAll(gwevent.getNameValuePairs());

		// Fill in other values to send to the server
		nameValuePairs.add(new BasicNameValuePair("id", Secure.getString(getBaseContext().getContentResolver(), Secure.ANDROID_ID)));
		dumbPairs.add(new BasicNameValuePair("h", Secure.getString(getBaseContext().getContentResolver(), Secure.ANDROID_ID).subSequence(0, 3).toString()));
		
		mGWID = new GridWatchID();
		dumbPairs.add(new BasicNameValuePair("u", mGWID.get_last_value()));
		
		try {
			dumbPairs.add(new BasicNameValuePair("v", getPackageManager().getPackageInfo(getPackageName(), 0).versionName.replace(".", "")));
		} catch (NameNotFoundException e) {
			dumbPairs.add(new BasicNameValuePair("v", "u"));
		}
		
		/*
		nameValuePairs.add(new BasicNameValuePair("phone_type", getDeviceName()));
		nameValuePairs.add(new BasicNameValuePair("os", "android"));
		nameValuePairs.add(new BasicNameValuePair("os_version", Build.VERSION.RELEASE));
		try {
			nameValuePairs.add(new BasicNameValuePair("app_version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName));
		} catch (NameNotFoundException e) {
			nameValuePairs.add(new BasicNameValuePair("app_version", "unknown"));
		}
		*/

		HttpPost httppost = new HttpPost(alertServerURL);
		try {
			UrlEncodedFormEntity postparams = new UrlEncodedFormEntity(dumbPairs);
			httppost.setEntity(postparams);

		} catch (UnsupportedEncodingException e1) {
			e1.printStackTrace();
		}

		Intent lIntent = new Intent(INTENT_NAME);
		lIntent.putExtra(INTENT_EXTRA_EVENT_TYPE, "event_post");
		String post_info = "";
		
		for (int i = 0; i < nameValuePairs.size(); i++) {
			NameValuePair item = nameValuePairs.get(i);
			post_info += item.getName() + "=" + item.getValue() + ", ";
		}
		
		/*
		for (NameValuePair item : nameValuePairs) {
			post_info += item.getName() + "=" + item.getValue() + ", ";
		}
		*/
		
		lIntent.putExtra(INTENT_EXTRA_EVENT_INFO, post_info);
		lIntent.putExtra(INTENT_EXTRA_EVENT_TIME, mDateFormat.format(new Date()));
		broadcastIntent(lIntent);

		mGWLogger.log(mDateFormat.format(new Date()), "event_post", post_info);

		// Debug
		/*
		for (NameValuePair item : dumbPairs) {
			Log.w(noteTag, item.getName() + "=" + item.getValue());
		}
		*/
		
		for (int i = 0; i < dumbPairs.size(); i++) {
			NameValuePair item = dumbPairs.get(i);
			Log.w(noteTag, item.getName() + "=" + item.getValue());
		}
		
		// Create the task to run in the background at some point in the future
		new PostAlertTask().execute(httppost);
	}

	// Returns the phone type for adding meta data to the transmitted packets
	private String getDeviceName() {
		String manufacturer = Build.MANUFACTURER;
		String model = Build.MODEL;
		if (model.startsWith(manufacturer)) {
			return capitalize(model);
		} else {
			return capitalize(manufacturer) + " " + model;
		}
	}

	private String capitalize(String s) {
		if (s == null || s.length() == 0) {
			return "";
		}
		char first = s.charAt(0);
		if (Character.isUpperCase(first)) {
			return s;
		} else {
			return Character.toUpperCase(first) + s.substring(1);
		}
	}

	private Location getLocationByProvider(String provider) {
		Location location = null;
		try {
			if (mLocationManager.isProviderEnabled(provider)) {
				location = mLocationManager.getLastKnownLocation(provider);
			}
		} catch (IllegalArgumentException e) { }
		return location;
	}

	@Override
	public IBinder onBind(Intent arg0) {
		// Service does not allow binding
		return null;
	}

	@Override
	public final void onAccuracyChanged(Sensor sensor, int accuracy) {
		// We don't really care about sensor accuracy that much; ignore
	}

	// Call to generate listeners that request the phones location.
	private void updateLocation () {

		for (String s : mLocationManager.getAllProviders()) {
			mLocationManager.requestLocationUpdates(s, LOCATION_WAIT_TIME, 0.0f, new LocationListener() {

				@Override
				public void onLocationChanged(Location location) {
					// Once we get a new location cancel our location updating
					mLocationManager.removeUpdates(this);
				}

				@Override
				public void onProviderDisabled(String provider) { }

				@Override
				public void onProviderEnabled(String provider) { }

				@Override
				public void onStatusChanged(String provider, int status, Bundle extras) { }
			});
		}
	}
}
