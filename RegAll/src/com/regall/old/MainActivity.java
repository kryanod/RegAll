package com.regall.old;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.provider.Settings;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Toast;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.MenuItem;
import com.crashlytics.android.Crashlytics;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient.ConnectionCallbacks;
import com.google.android.gms.common.GooglePlayServicesClient.OnConnectionFailedListener;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.regall.R;
import com.regall.old.db.DAOUserObject;
import com.regall.old.fragments.AddCarFragment;
import com.regall.old.fragments.AutowashListFragment;
import com.regall.old.fragments.BaseFragment;
import com.regall.old.fragments.CabinetFragment;
import com.regall.old.fragments.FilterFragment;
import com.regall.old.fragments.GeocodeFragment;
import com.regall.old.fragments.LoginFragment;
import com.regall.old.fragments.MainFragment;
import com.regall.fragments.MapFragment;
import com.regall.old.fragments.OrdersHistoryFragment;
import com.regall.old.fragments.RecentAutowashesFragment;
import com.regall.old.fragments.RegistrationFragment;
import com.regall.old.fragments.SelectWashServiceFragment;
import com.regall.old.fragments.SelectWashTimeFragment;
import com.regall.old.model.AdditionalService;
import com.regall.old.model.AutowashFilter;
import com.regall.old.model.AutowashFilter.LocationFilter;
import com.regall.old.model.User;
import com.regall.old.network.API;
import com.regall.old.network.Callback;
import com.regall.old.network.geocode.json.Result;
import com.regall.old.network.request.RequestGetUserObjects;
import com.regall.old.network.response.ResponseGetOrganizations.Point;
import com.regall.old.network.response.ResponseGetOrganizations.Point.ServiceDescription;
import com.regall.old.network.response.ResponseGetServices.Service;
import com.regall.old.network.response.ResponseGetUserObjects;
import com.regall.old.network.response.ResponseGetUserObjects.ClientObject;
import com.regall.old.utils.DialogHelper;
import com.regall.old.utils.Logger;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

@SuppressLint("InflateParams") 
public class MainActivity extends SherlockFragmentActivity implements ConnectionCallbacks, OnConnectionFailedListener, LocationListener {

	public final static String tag = MainActivity.class.getSimpleName();

	private final static int GOOGLE_PLAY_SERVICE_REQUEST = 9001;

	private API mApi;
	private ProgressDialog mProgressDialog;
	private AutowashFilter mCurrentAutowashFilter = new AutowashFilter(LocationFilter.CURRENT_LOCATION, new HashSet<Service>(), new HashSet<AdditionalService>());
	private User mUser;

	private LocationClient mLocationClient;
	private LocationRequest mLocationRequest;
	private Location mLocation;

	private LocationListener mFragmentLocationListener;
	private boolean mNeedInitializeView;
	
	private Callback<ResponseGetUserObjects> mGetObjectsCallback = new Callback<ResponseGetUserObjects>() {

		@Override
		public void success(Object object) {
			hideProgressDialog();
			ResponseGetUserObjects response = (ResponseGetUserObjects) object;
			if (response.isSuccess()) {
				List<ClientObject> clientObjects = response.getClientObjects();
				if(clientObjects != null){
					DAOUserObject dao = new DAOUserObject(MainActivity.this);
					dao.deleteAll();
					dao.insertAll(response.getClientObjects());
				}
			} else {
				showToast(response.getStatusDetail());
			}
		}

		@Override
		public void failure(Exception e) {
			hideProgressDialog();
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Crashlytics.start(this);
		
		String os = System.getProperty("os.version");
		int sdk = Build.VERSION.SDK_INT;
		String deviceName = Build.DEVICE;
		String model = Build.MODEL;

		String installId = deviceName + " " + model + " " + os + "(" + sdk + ")";
		Crashlytics.setApplicationInstallationIdentifier(installId);

		setContentView(R.layout.activity_main);
		setupActionBar();

		mApi = new API(getString(R.string.server_url));
		mApi.debug();

		mProgressDialog = new ProgressDialog(this);

		setupLocationClient();
	}
	
	private void setupLocationClient(){
		if(checkGoogleServiceAvailable()){
			mLocationClient = new LocationClient(this, this, this);
			mLocationRequest = LocationRequest.create().setInterval(5000).setFastestInterval(1000).setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY).setNumUpdates(1);
			mNeedInitializeView = true;
			if(mLocationClient.isConnected() || mLocationClient.isConnecting()){
				System.out.println("on init - is connected or connecting");
				showProgressDialog(R.string.message_update_location_in_progress);
				mLocationClient.requestLocationUpdates(mLocationRequest, this);
			} else {
				System.out.println("on init - not connected");
				showProgressDialog(R.string.message_update_location_in_progress);
				mLocationClient.connect();
			}
		}
	}
	
	private void initializeState(Location location){
		mUser = User.fromPreferences(this);
		showMapFragment(location.getLatitude(), location.getLongitude(), 0, 0);
		
		if(mUser != null){
			showProgressDialog(R.string.message_receiving_user_objects);
			RequestGetUserObjects request = RequestGetUserObjects.create(mUser.getPhone());
			mApi.getUserObjects(request, mGetObjectsCallback);
		}
	}
	
//	@Override
//	protected void onResume() {
//		super.onResume();
//		if (checkGoogleServiceAvailable()) {
//			mLocationClient.connect();
//		}
//	}

	@Override
	protected void onStop() {
		super.onStop();
		if (mLocationClient != null && mLocationClient.isConnected()) {
			System.out.println("Removing location updates");
			mLocationClient.removeLocationUpdates(this);
			mLocationClient.disconnect();
		}
	}

	private void setupActionBar() {
		ActionBar ab = getSupportActionBar();
		View customActionBar = getLayoutInflater().inflate(R.layout.action_bar_background, null);
		ActionBar.LayoutParams lp = new ActionBar.LayoutParams(ActionBar.LayoutParams.WRAP_CONTENT, ActionBar.LayoutParams.MATCH_PARENT, Gravity.CENTER);
		ab.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
		ab.setDisplayShowCustomEnabled(true);
		ab.setDisplayShowTitleEnabled(true);
		ab.setCustomView(customActionBar, lp);

		ab.setLogo(R.drawable.logo);
		ab.setTitle(R.string.app_name);

		ab.setDisplayUseLogoEnabled(true);
		ab.setDisplayShowHomeEnabled(true);
		ab.setDisplayHomeAsUpEnabled(true);
		ab.setHomeButtonEnabled(true);
	}

	public void loadFragment(BaseFragment fragment, boolean addToBackStack) {
		hideProgressDialog();

		FragmentManager manager = getSupportFragmentManager();
		FragmentTransaction transaction = manager.beginTransaction();

		transaction.replace(R.id.container, fragment, fragment.tag());
		if (addToBackStack) {
			transaction.addToBackStack(fragment.tag());
		}

		transaction.commit();
	}

	public void showMainFragment() {
		loadFragment(new MainFragment(), false);
	}

	public void showSearchFragment() {
		loadFragment(AutowashListFragment.create(mCurrentAutowashFilter), false);
	}

	public void showCabinetFragment() {
		loadFragment(new CabinetFragment(), true);
	}

	public void showOrdersHistoryFragment() {
		loadFragment(new OrdersHistoryFragment(), true);
	}

	public void showAutowashesFilter() {
		loadFragment(FilterFragment.create(mCurrentAutowashFilter), true);
	}

	public void showGeocodeFragment() {
		loadFragment(new GeocodeFragment(), true);
	}

	public void showLoginFragment() {
		loadFragment(new LoginFragment(), false);
	}

	public void showRegistrationFragment() {
		loadFragment(new RegistrationFragment(), true);
	}

	public void showMapFragment(double latFrom, double lonFrom, double latTo, double lonTo) {
		hideProgressDialog();

		FragmentManager manager = getSupportFragmentManager();
		FragmentTransaction transaction = manager.beginTransaction();

		MapFragment map = MapFragment.create(latFrom, lonFrom);
		
		transaction.replace(R.id.container, map, "map");
//		transaction.addToBackStack("map");

		transaction.commit();
	}

	public API getApi() {
		return mApi;
	}

	public void showToast(String message) {
		Toast.makeText(this, message, Toast.LENGTH_LONG).show();
	}

	public void showToast(int messageResourceId) {
		Toast.makeText(this, messageResourceId, Toast.LENGTH_LONG).show();
	}

	public void showSelectWashServiceFragment(Point organisation) {
		loadFragment(SelectWashServiceFragment.create(organisation), true);
	}

	public void showSelectWashTimeFragment(int objectId, String objectTitle, Point point, HashSet<ServiceDescription> selectedServices) {
		loadFragment(SelectWashTimeFragment.create(objectId, objectTitle, point, selectedServices), true);
	}

	public void showRecentAutowashes() {
		loadFragment(new RecentAutowashesFragment(), true);
	}

	public void showNewCardFragment() {
		loadFragment(new AddCarFragment(), true);
	}

	public void showProgressDialog(int messageId) {
		hideProgressDialog();
		mProgressDialog.setMessage(getString(messageId));
		mProgressDialog.show();
	}

	public void hideProgressDialog() {
		if (mProgressDialog != null && mProgressDialog.isShowing()) {
			mProgressDialog.hide();
		}
	}

	public void onGeopointSelectedAsFilter(Result geocodeResult) {
		FilterFragment fragment = getFragment(FilterFragment.class);
		if (fragment != null) {
			FilterFragment target = (FilterFragment) fragment;
			target.setLocationFilter(geocodeResult);
		} else {
			showToast(R.string.message_error_deliver_result_location);
		}

		getSupportFragmentManager().popBackStack();
	}

	public void applyAutowashFilter(AutowashFilter newFilter) {
		mCurrentAutowashFilter = newFilter;
		AutowashListFragment fragment = getFragment(AutowashListFragment.class);
		if (fragment != null) {
			fragment.applyFilter(newFilter);
		} else {
			showToast(R.string.message_error_apply_filter);
		}

		getSupportFragmentManager().popBackStack();
	}

	@SuppressWarnings("unchecked")
	public <T> T getFragment(Class<?> fragmentClass) {
		FragmentManager fManager = getSupportFragmentManager();
		Fragment fragment = fManager.findFragmentByTag(fragmentClass.getSimpleName());
		if (fragmentClass.isInstance(fragment)) {
			return (T) fragment;
		} else {
			return null;
		}
	}

	public void setUser(User user) {
		this.mUser = user;
	}

	public User getUser() {
		return mUser;
	}

	public void addCarToUserProfile() {
		CabinetFragment fragment = getFragment(CabinetFragment.class);
		if(fragment != null){
			fragment.setNeedUpdateUserObjects(true);
		}
		getSupportFragmentManager().popBackStack();
	}

	public void updateLocation(LocationListener locationListener) {
		Logger.logDebug(tag, "updateLocation");
		
		String locationMode = Settings.Secure.getString(getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
		Logger.logDebug(tag, "Location mode - " + locationMode);
		Log.i(tag, "Location mode - " + locationMode);
		
		if(locationMode == null || locationMode.isEmpty()){
			showEnableLocationSettingsDialog();
			return;
		}
		
		if (mLocation != null) {
			Logger.logDebug(tag, "updateLocation - old exists");
			locationListener.onLocationChanged(mLocation);
		} else {
			Logger.logDebug(tag, "updateLocation - not initialized, request for updates");
			mFragmentLocationListener = locationListener;
			
			if (!mLocationClient.isConnected() && checkGoogleServiceAvailable()) {
				mLocationClient.connect();
			}
		}
	}

	public boolean checkGoogleServiceAvailable() {
		Logger.logDebug(tag, "checkGooglePlayServiceAvailable");
		int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
		if (resultCode != ConnectionResult.SUCCESS) {
			if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
				GooglePlayServicesUtil.getErrorDialog(resultCode, this, GOOGLE_PLAY_SERVICE_REQUEST).show();
			} else {
				showToast(R.string.message_some_functions_unavailable);
			}
			return false;
		} else {
			return true;
		}
	}

	@Override
	public void onConnectionFailed(ConnectionResult arg0) {
		Logger.logDebug(tag, "onConnectionFailed - " + arg0.getErrorCode());
	}

	@Override
	public void onConnected(Bundle arg0) {
		Logger.logDebug(tag, "onConnected");
		Location lastLocation = mLocationClient.getLastLocation();
		if (lastLocation != null) {
			Logger.logDebug(tag, "last location is not null");
			mLocation = lastLocation;
		}

		Logger.logDebug(tag, "requesting location updates");
		mLocationRequest = LocationRequest.create().setInterval(5000).setFastestInterval(1000).setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY).setNumUpdates(1);
		mLocationClient.requestLocationUpdates(mLocationRequest, this);
	}

	@Override
	public void onDisconnected() {
		Logger.logDebug(tag, "on disconnected");
		mLocationClient.removeLocationUpdates(this);
	}

	@Override
	public void onLocationChanged(Location location) {
		Logger.logDebug(tag, "on location changed");
		mLocation = location;
		
		if(mNeedInitializeView){
			hideProgressDialog();
			mNeedInitializeView = false;
			initializeState(location);
		}

//		if (mFragmentLocationListener != null) {
//			mFragmentLocationListener.onLocationChanged(location);
//			mFragmentLocationListener = null;
//		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if(item.getItemId() == android.R.id.home){
			if(mUser != null){
				showCabinetFragment();
			} else {
				// TODO suggest to register
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	public void enableProfileButton() {
		ActionBar ab = getSupportActionBar();
		ab.setDisplayShowHomeEnabled(true);
		ab.setLogo(R.drawable.profile);
	}

	public void disableProfileButton() {
		ActionBar ab = getSupportActionBar();
		ab.setDisplayShowHomeEnabled(false);
		ab.setLogo(null);
	}
	
	public void resetBackStack(){
		clearBackStack();
		loadFragment(AutowashListFragment.create(mCurrentAutowashFilter), false);
	}
	
	private void clearBackStack(){
		FragmentManager fManager = getSupportFragmentManager();
		for(int i = 0, max = fManager.getBackStackEntryCount(); i < max; i++){
			fManager.popBackStack();
		}
	}
	
	public void logout(){
		if(mUser != null){
			mUser.logout(this);
		}
		finish();
	}
	
	private void showEnableLocationSettingsDialog(){
		Dialog dialog = DialogHelper.createConfirmDialog(this, R.string.dialog_title_error, getString(R.string.message_no_location_services_enabled), new DialogInterface.OnClickListener() {
			
			@Override
			public void onClick(DialogInterface dialog, int which) {
				startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
			}
		});
		
		dialog.show();
	}
	
	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH) public void addEventToCalendar(String when, String duration, Point point){
		String dateFormat = "dd.MM.yyyy HH:mm";
		String durationFormat = "HH:mm:ss";
		
		SimpleDateFormat formatterWhen = new SimpleDateFormat(dateFormat);
		SimpleDateFormat formatterDuration = new SimpleDateFormat(durationFormat);
		
		Calendar whenCalendar = null;
		Calendar overCalendar = null;
		
		try {
			Date whenDate = formatterWhen.parse(when);
			Date durationDate = formatterDuration.parse(duration);
			
			whenCalendar = Calendar.getInstance();
			whenCalendar.setTime(whenDate);
			
			overCalendar = Calendar.getInstance();
			overCalendar.setTime(durationDate);
			
			overCalendar.add(Calendar.HOUR_OF_DAY, whenCalendar.get(Calendar.HOUR_OF_DAY));
			overCalendar.add(Calendar.MINUTE, whenCalendar.get(Calendar.MINUTE));
			
		} catch (ParseException e) {
			e.printStackTrace();
		}

		Intent intent = new Intent(Intent.ACTION_INSERT);
		intent.setData(Events.CONTENT_URI);
		
		if(whenCalendar != null && overCalendar != null) {
			intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, whenCalendar.getTimeInMillis());
			intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, overCalendar.getTimeInMillis());
		}
		
		intent.putExtra(Events.TITLE, R.string.reminder_title);
		intent.putExtra(Events.EVENT_LOCATION, point.getAddress());
		
		try {
			startActivity(intent);
		} catch (ActivityNotFoundException e){
			intent.setAction(Intent.ACTION_EDIT);
			try {
				startActivity(intent);
			} catch (ActivityNotFoundException e1) {
				showToast(R.string.message_no_calendar);
			}
		}
		
	}
}