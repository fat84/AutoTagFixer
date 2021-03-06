package mx.dev.franco.automusictagfixer;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ShareCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.SimpleItemAnimator;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mx.dev.franco.automusictagfixer.database.DataTrackDbHelper;
import mx.dev.franco.automusictagfixer.database.TrackContract;
import mx.dev.franco.automusictagfixer.list.AudioItem;
import mx.dev.franco.automusictagfixer.list.TrackAdapter;
import mx.dev.franco.automusictagfixer.receivers.ResponseReceiver;
import mx.dev.franco.automusictagfixer.services.ConnectivityDetector;
import mx.dev.franco.automusictagfixer.services.FixerTrackService;
import mx.dev.franco.automusictagfixer.services.Job;
import mx.dev.franco.automusictagfixer.services.ServiceHelper;
import mx.dev.franco.automusictagfixer.utilities.Constants;
import mx.dev.franco.automusictagfixer.utilities.RequiredPermissions;
import mx.dev.franco.automusictagfixer.utilities.Settings;
import mx.dev.franco.automusictagfixer.utilities.SimpleMediaPlayer;
import mx.dev.franco.automusictagfixer.utilities.StorageHelper;

import static mx.dev.franco.automusictagfixer.services.GnService.sApiInitialized;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
    TrackAdapter.AudioItemHolder.ClickListener, ResponseReceiver.OnResponse {
    private static final long UPDATE_LIST = -2;
    public static String TAG = MainActivity.class.getName();

    //flag to indicate when the app is retrieving data from songs
    public static boolean sIsGettingData = false;

    //Indicates why cannot execute task twice at the same time
    public static final int PROCESSING_TASK = 42;

    //media player instance, only one is allowed
    public static SimpleMediaPlayer sMediaPlayer;
    //indicates there's no action to take
    private static final int NO_ID = -1;
    //object for sorting list
    private static TrackAdapter.Sorter sSorter;

    //Adapter of recyclerview
    private TrackAdapter mAudioItemArrayAdapter;
    //Data source to populate the adapter
    private List<AudioItem> mAudioItemList;

    //actions to indicate from where to retrieve data depending on is first use app or not
    private static final int RE_SCAN = 20;
    private static final int CREATE_DATABASE = 21;
    private static final int READ_FROM_DATABASE = 22;

    //view to show messages to user when permission to read files is not granted, or
    //in case there have no music files
    private TextView mSearchAgainMessageTextView;
    //search widget object, for search more quickly
    //any song by title in recyclerview list
    private SearchView mSearchViewWidget;
    //FloatingActionButton, this executes main task: correct a bunch of selected tracks;
    //this executes the automatic mode, without intervention of user,
    private FloatingActionButton mFloatingActionButtonStart;
    //This FloatingActionButton can cancel the task, in case the user decides it.
    private FloatingActionButton mFloatingActionButtonStop;
    //swipe refresh layout for give to user the
    //ability to re scan his/her library making a swipe down gesture,
    //this is a material design pattern
    private SwipeRefreshLayout mSwipeRefreshLayout;
    //recycler view is a component that delivers
    //better performance with huge data sources
    private RecyclerView mRecyclerView;
    //instance to connection to database
    private DataTrackDbHelper mDataTrackDbHelper;
    //local broadcast for handling responses from FixerTrackService.
    private LocalBroadcastManager mLocalBroadcastManager;
    //these filters help to indicate which intents can be
    //received and handled from FixerTrackService.
    private IntentFilter mFilterActionDone;
    private IntentFilter mFilterActionCancel;
    private IntentFilter mFilterActionCompleteTask;
    private IntentFilter mFilterActionFail;
    private IntentFilter mFilterApiInitialized;
    private IntentFilter mFilterActionSetAudioProcessing;
    private IntentFilter mFilterActionNotFound;
    private IntentFilter mFilterActionConnectionLost;
    private IntentFilter mFilterActionRequestUpdateList;
    private IntentFilter mFilterActionErrorApi;
    //the receiver that handles the intents from FixerTrackService
    private mx.dev.franco.automusictagfixer.receivers.ResponseReceiver mReceiver;

    //global references to menu items
    private MenuItem mMenuItemPath, mMenuItemTitle, mMenuItemArtist, mMenuItemAlbum;
    private MenuItem lastCheckedItem;
    private Menu mMenu;
    //contextual Toolbar
    private Toolbar mToolbar;

    //snackbar is a bottom UI component
    //to indicate to user what is happening
    //when a correction task is in progress
    private Snackbar mSnackbar;
    //a reference to action bar to making use
    //of some useful methods from this object
    private ActionBar mActionBar;

    //This async task retrieves the data from songs
    //to populate the adapter, or executes an update
    //for searching new songs and eliminate from list
    //those whose don't exist anymore in smartphone
    private AsyncFileReader mAsyncFileReader;
    private GridLayoutManager mGridLayoutManager;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //windows is the top level in the view hierarchy,
        //it has a single Surface in which the contents of the window is rendered
        //A Surface is an object holding pixels that are being composited to the screen.
        Window window = getWindow();
        window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.setAllowEnterTransitionOverlap(true);
        window.setAllowReturnTransitionOverlap(true);
        window.requestFeature(Window.FEATURE_ACTION_MODE_OVERLAY);
        window.requestFeature(Window.FEATURE_CONTENT_TRANSITIONS);


        setContentView(R.layout.activity_main);

        //get unique instances from database connection and media player
        mDataTrackDbHelper = DataTrackDbHelper.getInstance(getApplicationContext());
        sMediaPlayer = SimpleMediaPlayer.getInstance(getApplicationContext());

        //create filters to listen for response from FixerTrackService
        mFilterActionDone = new IntentFilter(Constants.Actions.ACTION_DONE);
        mFilterActionCancel = new IntentFilter(Constants.Actions.ACTION_CANCEL_TASK);
        mFilterActionNotFound = new IntentFilter(Constants.Actions.ACTION_NOT_FOUND);
        mFilterActionCompleteTask = new IntentFilter(Constants.Actions.ACTION_COMPLETE_TASK);
        mFilterActionFail = new IntentFilter(Constants.Actions.ACTION_FAIL);
        mFilterApiInitialized = new IntentFilter(Constants.GnServiceActions.ACTION_API_INITIALIZED);
        mFilterActionSetAudioProcessing = new IntentFilter(Constants.Actions.ACTION_SET_AUDIOITEM_PROCESSING);
        mFilterActionConnectionLost = new IntentFilter(Constants.Actions.ACTION_CONNECTION_LOST);
        mFilterActionRequestUpdateList = new IntentFilter(Constants.Actions.ACTION_REQUEST_UPDATE_LIST);
        mFilterActionErrorApi = new IntentFilter(Constants.Actions.ACTION_ERROR);

        //create receiver
        mReceiver = new mx.dev.franco.automusictagfixer.receivers.ResponseReceiver(this, new Handler());
        //get instance of local broadcast manager
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(getApplicationContext());

        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        mFloatingActionButtonStart = (FloatingActionButton) findViewById(R.id.fab_start);
        mFloatingActionButtonStop = (FloatingActionButton) findViewById(R.id.fab_stop);
        mSearchAgainMessageTextView = (TextView) findViewById(R.id.genericMessage);
        //Initialize recycler view and swipe refresh layout
        mSwipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.refreshLayout);

        mSwipeRefreshLayout.setColorSchemeColors(ContextCompat.getColor(getApplicationContext(),R.color.primaryColor));
        mSwipeRefreshLayout.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(getApplicationContext(),R.color.grey_900));

        mRecyclerView = (RecyclerView) findViewById(R.id.recyclerView);
        //layout manager gives the ability to show elements in different
        //ways, for example, LinearLayoutManager, shows as a list, but we can use
        //GridLayoutManager and our elements will be shown as mosaics.
        mGridLayoutManager = new GridLayoutManager(this, 1);
        mRecyclerView.setLayoutManager(mGridLayoutManager);
        //mRecyclerView.setLayoutManager( new LinearLayoutManager(this));
        //mRecyclerView.setLayoutManager( new GridLayoutManager(this,2));
        //this options gives us more improvements to our list
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.setItemViewCacheSize(10);
        mRecyclerView.setDrawingCacheEnabled(true);
        mRecyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_LOW);
        //enable sound and vibration when touch an element
        mRecyclerView.setHapticFeedbackEnabled(true);
        mRecyclerView.setSoundEffectsEnabled(true);
        ((SimpleItemAnimator) mRecyclerView.getItemAnimator()).setSupportsChangeAnimations(false);

        //get the actionbar to enable some extra functionality
        //to toolbar
        setSupportActionBar(mToolbar);
        mActionBar = getSupportActionBar();

        //hide FloatingActionButton, then show if and only if list has been created
        mFloatingActionButtonStart.hide();
        mFloatingActionButtonStop.hide();
        //create data source and adapter
        mAudioItemList = new ArrayList<>();
        mAudioItemArrayAdapter = new TrackAdapter(mAudioItemList,this);
        //sorting object
        sSorter = TrackAdapter.Sorter.getInstance();

        //attach the adapter to media player
        sMediaPlayer.setAdapter(mAudioItemArrayAdapter);
        //we create a snack bar for messages
        createSnackBar();

        //attach adapter to our recyclerview
        mRecyclerView.setAdapter(mAudioItemArrayAdapter);
        //Lets implement functionality for refresh layout listener
        mSwipeRefreshLayout.setColorSchemeColors(
                ContextCompat.getColor(getApplicationContext(), R.color.primaryColor),
                ContextCompat.getColor(getApplicationContext(), R.color.primaryDarkColor),
                ContextCompat.getColor(getApplicationContext(), R.color.primaryLightColor)
        );
        mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                mSearchAgainMessageTextView.setVisibility(View.GONE);
                //If not permission granted show the reason to access files until user grant permission
                if(!RequiredPermissions.ACCESS_GRANTED_FILES) {
                    showRequestPermissionReason();
                }
                else {
                    //after onStop, we can save a
                    //if we have the permission, check Bundle object to verify if the activity comes from onPause or from onCreate event
                    //if(savedInstanceState == null){
                        //int taskType = DataTrackDbHelper.existDatabase(getApplicationContext()) && mDataTrackDbHelper.getCount(null) > 0 ? READ_FROM_DATABASE : CREATE_DATABASE;
                        mAsyncFileReader = new AsyncFileReader(RE_SCAN, MainActivity.this);
                        mAsyncFileReader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    //}

                }
            }

        });



        //If we already had the permission granted, lets go  read data from database and pass them to adapter, to show in Recyclerview,
        //otherwise we show the reason to access files until user grant permission

        if(ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            showRequestPermissionReason();
        }
        else {
            //if we have the permission, check Bundle object to verify if the activity comes from onPause or from onCreate
            //if(savedInstanceState == null){
                mRecyclerView.setAdapter(mAudioItemArrayAdapter);
                int taskType = DataTrackDbHelper.existDatabase(this) && mDataTrackDbHelper.getCount() > 0 ? READ_FROM_DATABASE : CREATE_DATABASE;

                mAsyncFileReader = new AsyncFileReader(taskType, this);
                mAsyncFileReader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            //}

        }

        mFloatingActionButtonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTask();
            }
        });

        mFloatingActionButtonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopTask();
            }
        });

        //Iif service is running currently, then register receiver and filters to update UI
        if(ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning()) {
            registerReceivers();
            mFloatingActionButtonStart.hide();
            mFloatingActionButtonStop.show();
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, mToolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        Log.d(TAG,"onCreate");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mRecyclerView.stopScroll();
        mGridLayoutManager.setSpanCount(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ? 2 : 1);
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG,"onStart");

    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState){
        super.onRestoreInstanceState(savedInstanceState);

        mFloatingActionButtonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTask();
            }
        });

        mFloatingActionButtonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopTask();
            }
        });

        //restore state of this components when restore event be fired
        //Actually if service is running, then register filters and receiver immediately to update UI
        if(ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning()) {
            registerReceivers();
            mFloatingActionButtonStart.hide();
            mFloatingActionButtonStop.show();
        }

        if(mRecyclerView.getAdapter() == null)
            mRecyclerView.setAdapter(mAudioItemArrayAdapter);
        Log.d(TAG,"onRestoreInstanceState");

    }


    @Override
    protected void onResume(){
        super.onResume();
        //register this filter only in case internet connection could not
        //be stablished at splash, and then receive a notification when a connection
        //could be stablished
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterApiInitialized);
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionRequestUpdateList);
        Log.d(TAG,"onResume");
    }

    @Override
    protected void onPause(){
        super.onPause();
        Log.d(TAG,"onPause");
        //Deregister filters if FixerTrackService if not processing any task,
        //useful for saving resources
        mRecyclerView.stopScroll();
        if(!ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning())
            mLocalBroadcastManager.unregisterReceiver(mReceiver);
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState){
        Log.d(TAG,"onSaveInstanceState");
        super.onSaveInstanceState(savedInstanceState);

    }

    @Override
    public void onStop() {
        Log.d(TAG,"onStop");
        mRecyclerView.stopScroll();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        //Before app closes, check if "Usar correccion en segundo plano" is OFF, in this case cancel the current task and
        // release resources used by DB connection
        if (ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning()
                && !Settings.BACKGROUND_CORRECTION) {
            Toast toast = Toast.makeText(getApplicationContext(), getString(R.string.cancelling), Toast.LENGTH_LONG);
            View view = toast.getView();
            TextView text = (TextView) view.findViewById(android.R.id.message);
            text.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.grey_900));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                text.setTextAppearance(R.style.CustomToast);
            }
            else {
                text.setTextAppearance(getApplicationContext(),R.style.CustomToast);
            }
            view.setBackground(ContextCompat.getDrawable(getApplicationContext(), R.drawable.background_custom_toast) );
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();

            Intent intentStopService = new Intent(this, FixerTrackService.class);
            intentStopService.setAction(Constants.Actions.ACTION_STOP_SERVICE);
            intentStopService.putExtra(Constants.Actions.ACTION_STOP_SERVICE, Constants.StopsReasons.CANCEL_TASK);
            startService(intentStopService);
            if (mDataTrackDbHelper != null) {
                mDataTrackDbHelper.close();
                mDataTrackDbHelper = null;
            }
        }
        //if "Usar correccion en segundo plano" is ON, task will continue working after app closes.
        //DB connection will not close because service will continue updating data in background thread.

        //when app closes the app, unregister receiver, thus is not necessary to listen
        //any broadcast to update UI
        mLocalBroadcastManager.unregisterReceiver(mReceiver);
        sMediaPlayer.setAdapter(null);
        mRecyclerView.stopScroll();

        if (mAudioItemArrayAdapter != null) {
            mAudioItemArrayAdapter.releaseResources();
            mAudioItemArrayAdapter = null;
        }

        mRecyclerView = null;
        mActionBar = null;

        if (mAudioItemList != null){
            mAudioItemList.clear();
            mAudioItemList = null;
        }

        mFilterActionCancel = null;
        mFilterActionCompleteTask = null;
        mFilterActionConnectionLost = null;
        mFilterActionDone = null;
        mFilterActionFail = null;
        mFilterActionNotFound = null;
        mFilterActionSetAudioProcessing = null;
        mFilterApiInitialized = null;
        mFloatingActionButtonStart = null;
        mFloatingActionButtonStop = null;
        mMenu = null;
        mMenuItemAlbum = null;
        mMenuItemArtist = null;
        mMenuItemPath = null;
        mMenuItemTitle = null;
        mLocalBroadcastManager = null;
        mSwipeRefreshLayout = null;
        mSnackbar = null;
        System.gc();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        }
        else {

        if(mAsyncFileReader != null && (mAsyncFileReader.getStatus() == AsyncTask.Status.RUNNING || mAsyncFileReader.getStatus() == AsyncTask.Status.PENDING)){
            mAsyncFileReader.cancel(true);
        }

            super.onBackPressed();
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.

        getMenuInflater().inflate(R.menu.menu_main_activity, menu);
        //get global reference to menu, useful for manipulating where necessary
        mMenu = menu;

        MenuItem searchItem = menu.findItem(R.id.action_search);
        //get a global reference to search widget


        //get global references to sort types actions
        mMenuItemPath = menu.findItem(R.id.action_sort_by_path);
        mMenuItemTitle = menu.findItem(R.id.action_sort_by_title);
        mMenuItemArtist = menu.findItem(R.id.action_sort_by_artist);
        mMenuItemAlbum = menu.findItem(R.id.action_sort_by_album);

        checkMenuItem(null);
        // Define an expand listener for search widget
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mSearchViewWidget = (SearchView) searchItem.getActionView();
            MenuItem.OnActionExpandListener expandListener = new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionCollapse(MenuItem item) {
                    //when no searching a track, activate refresh listener
                    //of swipe layouy
                    if(mSwipeRefreshLayout != null) {
                        mSwipeRefreshLayout.setEnabled(true);
                    }
                    //reset filter to show all tracks
                    if(mAudioItemArrayAdapter != null) {
                        mAudioItemArrayAdapter.getFilter().filter("");
                    }
                    //and show floating action menu if user have songs
                    if(mAudioItemList.size() != 0)
                        mFloatingActionButtonStart.show();
                    //finally remove listener
                    mSearchViewWidget.setOnQueryTextListener(null);
                    return true;  // Return true to collapse action widget
                }

                @Override
                public boolean onMenuItemActionExpand(MenuItem item) {

                    boolean isServiceRunning = ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning();
                    if(isServiceRunning){
                        showSnackBar(Snackbar.LENGTH_LONG,getString(R.string.processing_task),-1);
                        return false;
                    }


                    //if no songs, no case to expand the search widget
                    if(mAudioItemList.size() <= 0){
                        showSnackBar(Snackbar.LENGTH_LONG, getString(R.string.no_items_found),NO_ID);
                        return false;
                    }
                    //when searching a song, deactivate the swipe refresh layout
                    //and don't let update with swipe gesture
                    if (mSwipeRefreshLayout != null){
                        mSwipeRefreshLayout.setEnabled(false);
                    }
                    //when app is reading data from DB
                    //do not let searching to avoid an error
                    if(sIsGettingData){
                        showSnackBar(Snackbar.LENGTH_LONG,getString(R.string.getting_data),NO_ID);
                        return false;
                    }

                    //then if user is searching a song, hide the fab button
                    mFloatingActionButtonStart.hide();

                    //attach a listener that returns results while user is searching his/her song
                    mSearchViewWidget.setOnQueryTextListener(new SearchView.OnQueryTextListener(){

                        @Override
                        public boolean onQueryTextSubmit(String query) {
                            return true;
                        }

                        @Override
                        public boolean onQueryTextChange(String newText) {
                            mRecyclerView.stopScroll();

                            if(mAudioItemArrayAdapter != null) {
                                mAudioItemArrayAdapter.getFilter().filter(newText);
                            }
                            else {
                                showSnackBar(Snackbar.LENGTH_SHORT,getString(R.string.no_items_found),-1);
                            }
                            return true;
                        }
                    });
                    return true;  // Return true to expand action mSwipeRefreshLayout
                }
            };

            // Assign the listener to searchItem
            searchItem.setOnActionExpandListener(expandListener);
        }
        else {
            mSearchViewWidget = (SearchView) MenuItemCompat.getActionView(searchItem);
            MenuItemCompat.OnActionExpandListener expandListener = new MenuItemCompat.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionCollapse(MenuItem item) {
                    boolean isServiceRunning = ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning();
                    if(isServiceRunning){
                        showSnackBar(Snackbar.LENGTH_LONG,getString(R.string.processing_task),-1);
                        return false;
                    }

                    //when no searching a track, activate refresh listener
                    //of swipe layouy
                    if(mSwipeRefreshLayout != null) {
                        mSwipeRefreshLayout.setEnabled(true);
                    }
                    //reset filter to show all tracks
                    if(mAudioItemArrayAdapter != null) {
                        mAudioItemArrayAdapter.getFilter().filter("");
                    }
                    //and show floating action menu if user have songs
                    if(mAudioItemList.size() != 0)
                        mFloatingActionButtonStart.show();
                    //finally remove listener
                    mSearchViewWidget.setOnQueryTextListener(null);
                    return true;  // Return true to collapse action widget
                }

                @Override
                public boolean onMenuItemActionExpand(MenuItem item) {
                    //if no songs, no case to expand the search widget

                    if(mAudioItemList.size() <= 0){
                        showSnackBar(Snackbar.LENGTH_LONG, getString(R.string.no_items_found),NO_ID);
                        return false;
                    }
                    //when searching a song, deactivate the swipe refresh layout
                    //and don't let update with swipe gesture
                    if (mSwipeRefreshLayout != null){
                        mSwipeRefreshLayout.setEnabled(false);
                    }
                    //when app is reading data from DB
                    //do not let searching to avoid an error
                    if(sIsGettingData){
                        showSnackBar(Snackbar.LENGTH_LONG,getString(R.string.getting_data),NO_ID);
                        return false;
                    }

                    //then if user is searching a song, hide the fab button
                    mFloatingActionButtonStart.hide();

                    //attach a listener that returns results while user is searching his/her song
                    mSearchViewWidget.setOnQueryTextListener(new SearchView.OnQueryTextListener(){

                        @Override
                        public boolean onQueryTextSubmit(String query) {
                            return true;
                        }

                        @Override
                        public boolean onQueryTextChange(String newText) {
                            mRecyclerView.stopScroll();
                            if(mAudioItemArrayAdapter != null) {
                                mAudioItemArrayAdapter.getFilter().filter(newText);
                            }
                            else {
                                showSnackBar(Snackbar.LENGTH_SHORT,getString(R.string.no_items_found),-1);
                            }
                            return true;
                        }
                    });
                    return true;  // Return true to expand action mSwipeRefreshLayout
                }
            };

            // Assign the listener to searchItem
            MenuItemCompat.setOnActionExpandListener(searchItem, expandListener);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item_list clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        if(sIsGettingData){
            showSnackBar(Snackbar.LENGTH_LONG,getString(R.string.getting_data),NO_ID);
            return false;
        }

        int id = item.getItemId();

        switch (id){
            case R.id.action_select_all:
                checkAllItems();
                break;

            case R.id.action_refresh:
                refreshList();
                break;

            case R.id.path_asc:
                sortBy(TrackContract.TrackData.DATA, TrackAdapter.ASC);
                checkMenuItem(item);
                break;
            case R.id.path_desc:
                sortBy(TrackContract.TrackData.DATA, TrackAdapter.DESC);
                checkMenuItem(item);
                break;

            case R.id.title_asc:
                sortBy(TrackContract.TrackData.TITLE, TrackAdapter.ASC);
                checkMenuItem(item);
                break;
            case R.id.title_desc:
                sortBy(TrackContract.TrackData.TITLE, TrackAdapter.DESC);
                checkMenuItem(item);
                break;

            case R.id.artist_asc:
                sortBy(TrackContract.TrackData.ARTIST, TrackAdapter.ASC);
                checkMenuItem(item);
                break;
            case R.id.artist_desc:
                sortBy(TrackContract.TrackData.ARTIST, TrackAdapter.DESC);
                checkMenuItem(item);
                break;

            case R.id.album_asc:
                sortBy(TrackContract.TrackData.ALBUM, TrackAdapter.ASC);
                checkMenuItem(item);
                break;
            case R.id.album_desc:
                sortBy(TrackContract.TrackData.ALBUM, TrackAdapter.DESC);
                checkMenuItem(item);
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    private void checkMenuItem(MenuItem item){
        if(mAsyncFileReader != null && mAsyncFileReader.getStatus() == AsyncTask.Status.RUNNING){
            return;
        }

        //can't check an item while service is running and correcting songs, so wait until processing finish
        if(ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning()){
            showSnackBar(Snackbar.LENGTH_LONG,getString(R.string.processing_task),-1);
            return;
        }
        int id;
        if(item != null) {
            id = item.getItemId();
            //reset indicator in previous item selected
            if(lastCheckedItem != null)
                lastCheckedItem.setIcon(null);

            //it references the new checked item
            lastCheckedItem = item;
            lastCheckedItem.setIcon(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_done_white));
            SharedPreferences preferences = getSharedPreferences(Constants.Application.FULL_QUALIFIED_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = preferences.edit();
            Log.d("id_sort_item",id+"");
            editor.putInt(Constants.SORT_KEY,id);
            editor.apply();
            preferences = null;
            editor = null;
        }
        else {
            //default checked is SortBy path, ascendant order if no sort order
            //is provided
            id = Settings.SETTING_SORT;
            //Log.d("el id", id + "");
            lastCheckedItem = mMenu.findItem(id == 0  ? R.id.path_asc : id);
            if(lastCheckedItem != null) {
                lastCheckedItem.setIcon(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_done_white));
            }
            else {
                lastCheckedItem = mMenu.findItem(R.id.path_asc);
                lastCheckedItem.setIcon(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_done_white));
            }
        }

        //mark sort type and reset icon of other UI elements
        switch (id){
            case R.id.path_asc:
            case R.id.path_desc:
            case 0:
            case 1:
                mMenuItemTitle.setIcon(null);
                mMenuItemArtist.setIcon(null);
                mMenuItemAlbum.setIcon(null);
                mMenuItemPath.setIcon(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_done_white));
                break;

            case R.id.title_asc:
            case R.id.title_desc:
                mMenuItemArtist.setIcon(null);
                mMenuItemAlbum.setIcon(null);
                mMenuItemPath.setIcon(null);
                mMenuItemTitle.setIcon(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_done_white));
                break;

            case R.id.artist_asc:
            case R.id.artist_desc:
                mMenuItemTitle.setIcon(null);
                mMenuItemAlbum.setIcon(null);
                mMenuItemPath.setIcon(null);
                mMenuItemArtist.setIcon(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_done_white));
                break;

            case R.id.album_asc:
            case R.id.album_desc:
                mMenuItemTitle.setIcon(null);
                mMenuItemArtist.setIcon(null);
                mMenuItemPath.setIcon(null);
                mMenuItemAlbum.setIcon(ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_done_white));
                break;
        }
        //Sets the current sort in static field, so when user
        //starts a correction task in automatic mode, the recycler view be able
        //to scroll in order in which the songs were checked
        Settings.SETTING_SORT = id;
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation mSwipeRefreshLayout item_list clicks here.

        int id = item.getItemId();

        if (id == R.id.rate) {
            rateApp();
        } else if (id == R.id.share) {
            String shareSubText = getString(R.string.app_name) + " " + getString(R.string.share_message);
            String shareBodyText = getPlayStoreLink();

            Intent shareIntent = ShareCompat.IntentBuilder.from(this).setType("text/plain").setText(shareSubText +"\n"+ shareBodyText).getIntent();
            shareIntent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(shareIntent);
        }
        else if(id == R.id.settings){
        //configure app settings
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        }
        else if(id == R.id.faq){
                Intent intent = new Intent(this,QuestionsActivity.class);
                startActivity(intent);
        }
        else if(id == R.id.about){
            Intent intent = new Intent(this,ScrollingAboutActivity.class);
            startActivity(intent);
        }


        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private String getPlayStoreLink(){
        final String appPackageName = getApplicationContext().getPackageName();
        //String strAppLink = null;

        /*try {
            strAppLink = "market://details?id=" + appPackageName;
        }
        catch (ActivityNotFoundException e) {
            e.printStackTrace();
            strAppLink = "https://play.google.com/store/apps/details?id=" + appPackageName;
        }*/
        //finally {
            return "https://play.google.com/store/apps/details?id=" + appPackageName;
        //}
    }

    private void rateApp(){
        String packageName = getApplicationContext().getPackageName();
        Uri uri = Uri.parse("market://details?id=" + packageName);
        Intent goToMarket = new Intent(Intent.ACTION_VIEW, uri);
        // To count with Play market backstack, after pressing back button,
        // to taken back to our application, we need to add following flags to intent.
        goToMarket.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        try {
            startActivity(goToMarket);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,  Uri.parse("http://play.google.com/store/apps/details?id=" + packageName)));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        //Check permission to access files and execute scan if were granted
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setFilePermissionGranted();
            executeScan();
        }
        //if not, show a message to indicate to user to swipe up to refresh
        else{
            mSwipeRefreshLayout.setRefreshing(false);
            mSearchAgainMessageTextView.setText(R.string.swipe_up_search);
            mSearchAgainMessageTextView.setVisibility(View.VISIBLE);
            mFloatingActionButtonStart.hide();
        }

    }


    /**
     * Handles click for every item_list in recycler view
     * @param position
     * @param view
     */
    @Override
    public void onItemClicked(int position, View view) {


        switch (view.getId()) {
            case R.id.coverArt:
                onClickCoverArt(view, position, Constants.CorrectionModes.VIEW_INFO);
                break;
            case R.id.checkBoxTrack:
                checkItem((long)view.getTag(), view, position);
                break;
            case R.id.checkMark:
                AudioItem audioItem = mAudioItemArrayAdapter.getAudioItemByPosition(position);
                Toast toast = Toast.makeText(this, getStatusText(audioItem.getStatus()), Toast.LENGTH_LONG);
                View v = toast.getView();
                TextView text = v.findViewById(android.R.id.message);
                text.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.grey_900));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    text.setTextAppearance(R.style.CustomToast);
                }
                else {
                    text.setTextAppearance(this,R.style.CustomToast);
                }
                v.setBackground(ContextCompat.getDrawable(getApplicationContext(), R.drawable.background_custom_toast) );
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
                break;
            default:
                try {
                    correctSong(view, position);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
                break;
        }
    }

    /**
     * Refresh list by searching new and removed audio files
     * from smartphone
     */
    private void refreshList(){
        //request permission in case is necessary and update
        //our database
        if(RequiredPermissions.ACCESS_GRANTED_FILES) {
            mRecyclerView.stopScroll();
            mAsyncFileReader = new AsyncFileReader(RE_SCAN, this);
            mAsyncFileReader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        else {
            showRequestPermissionReason();
        }
    }

    /**
     * Converts from status code from audioitem object
     * to human readable status text
     * @return msg Is the string code
     */

    private String getStatusText(int status){
        String msg = "";
        switch (status){
            case AudioItem.STATUS_ALL_TAGS_FOUND:
                msg = getResources().getString(R.string.file_status_ok);
                break;
            case AudioItem.STATUS_ALL_TAGS_NOT_FOUND:
                msg = getResources().getString(R.string.file_status_incomplete);
                break;
            case AudioItem.STATUS_NO_TAGS_FOUND:
                msg = getResources().getString(R.string.file_status_bad);
                break;
            case AudioItem.STATUS_TAGS_EDITED_BY_USER:
                msg = getResources().getString(R.string.file_status_edit_by_user);
                break;
            case AudioItem.FILE_ERROR_READ:
                msg = getString(R.string.file_status_error_read);
                break;
            case AudioItem.STATUS_TAGS_CORRECTED_BY_SEMIAUTOMATIC_MODE:
                msg = getString(R.string.file_status_corrected_by_semiautomatic_mode);
                break;
            case AudioItem.STATUS_FILE_IN_SD_WITHOUT_PERMISSION:
                msg = getString(R.string.file_status_in_sd_without_permission);
                break;
            case AudioItem.STATUS_COULD_NOT_APPLIED_CHANGES:
                msg = getString(R.string.could_not_apply_changes);
                break;
            case AudioItem.STATUS_COULD_RESTORE_FILE_TO_ITS_LOCATION:
                msg = getString(R.string.could_not_copy_to_its_original_location);
                break;
            case AudioItem.STATUS_COULD_NOT_CREATE_AUDIOFILE:
                msg = getString(R.string.could_not_create_audiofile);
                break;
            case AudioItem.STATUS_COULD_NOT_CREATE_TEMP_FILE:
                msg = getString(R.string.could_not_create_temp_file);
                break;
            default:
                msg = getResources().getString(R.string.file_status_no_processed);
                break;
        }

        return msg;
    }


    /**
     * Sets the action to floating action button (mFloatingActionButtonStart)
     */
    private void startTask(){

        //Before starts task could permission were denied, so check them
        if(ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        }
        else {

            //Automatic mode require some conditions to execute
                int canContinue = allowExecute(getApplicationContext());

                if(canContinue != 0){
                    setSnackBarMessage(canContinue);
                    return;
                }

                if(mAudioItemArrayAdapter.getCountSelectedItems() == 0){
                    showSnackBar(Snackbar.LENGTH_LONG, getString(R.string.no_songs_to_correct), NO_ID);
                    return;
                }

                //Register receivers to update UI and start correction in automatic mode
                registerReceivers();
                Intent intent = new Intent(MainActivity.this, FixerTrackService.class);
                intent.putExtra(Constants.Activities.FROM_EDIT_MODE, false);

                //If "Correccion en segundo plano" is ON, start a foreground notification in case user closes the app
                if(Settings.BACKGROUND_CORRECTION)
                    intent.putExtra(Constants.Actions.ACTION_SHOW_NOTIFICATION, true);

                startService(intent);
                mFloatingActionButtonStart.hide();
                mFloatingActionButtonStop.show();
            }



    }

    /**
     * This method creates a general mSnackbar,
     * for recycle its use
     */
    private void createSnackBar() {
        mSnackbar = Snackbar.make(mSwipeRefreshLayout,"",Snackbar.LENGTH_SHORT);
        TextView tv = (TextView) this.mSnackbar.getView().findViewById(android.support.design.R.id.snackbar_text);

        mSnackbar.getView().setBackgroundColor(ContextCompat.getColor(getApplicationContext(),R.color.primaryLightColor));
        tv.setTextColor(ContextCompat.getColor(getApplicationContext(),R.color.grey_800));
        mSnackbar.setActionTextColor(ContextCompat.getColor(getApplicationContext(),R.color.grey_800));

    }

    /**
     * @param duration
     * @param msg
     * @param id
     */
    private void showSnackBar(int duration, String msg, final long id){

        if(mSnackbar != null) {
            mSnackbar = null;
            createSnackBar();
        }

        //no action if no ID
        if(id == -1){
            mSnackbar.setText(msg);
            mSnackbar.setDuration(duration);
            mSnackbar.setAction("",null);
        }
        else if( id == -2){
            //Suggest to user to update list
            mSnackbar.setText(msg);
            mSnackbar.setDuration(duration);
            mSnackbar.setAction(R.string.update_list, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                mAsyncFileReader = new AsyncFileReader(RE_SCAN, MainActivity.this);
                mAsyncFileReader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
            });
        }

        else {
            //Suggest to user to correct song manually, if no tags were found
            mSnackbar.setText(msg);
            mSnackbar.setDuration(duration);
            mSnackbar.setAction(R.string.manual_mode, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent2 = new Intent(MainActivity.this, TrackDetailsActivity.class);
                    intent2.putExtra(Constants.MEDIA_STORE_ID,id);
                    intent2.putExtra(Constants.CorrectionModes.MODE, Constants.CorrectionModes.MANUAL);
                    startActivity(intent2);
                }
            });
        }

        mSnackbar.show();
    }

    /**
     * Automatic, semiautomatic and download cover mode
     * require internet and initialized GNSDK API,
     * this method verifies these conditions are met.
     * Also if a correction task is in progress, it will not
     * start a new one until current finishes
     * @param appContext
     * @return
     */
    public static int allowExecute(Context appContext){
        Context context = appContext.getApplicationContext();
        //No internet connection
        if(!ConnectivityDetector.sIsConnected){
            ConnectivityDetector.withContext(appContext).startCheckingConnection();
            return Constants.Conditions.NO_INTERNET_CONNECTION;
        }

        //API not initialized
        if(!sApiInitialized){
            Job.scheduleJob(context);
            return Constants.Conditions.NO_INITIALIZED_API;
        }

        //A correction task is already in progress
        if(ServiceHelper.withContext(appContext).withService(FixerTrackService.CLASS_NAME).isServiceRunning()){
            return PROCESSING_TASK;
        }

        return 0;
    }

    /**
     * This method mark as select all
     * items in recycler view
     */
    private void checkAllItems(){

        if(mAudioItemList.size() == 0 ){
            showSnackBar(Snackbar.LENGTH_SHORT, getString(R.string.no_items), NO_ID);
            return;
        }

        //wait until processing finish
        if(ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning()){
            showSnackBar(Snackbar.LENGTH_LONG,getString(R.string.processing_task),-1);
            return;
        }

        final boolean allSelected = mAudioItemArrayAdapter.areAllChecked();
        mRecyclerView.stopScroll();
        //Make DB operations in other thread for not blocking UI thread
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                ContentValues values = new ContentValues();
                values.put(TrackContract.TrackData.IS_SELECTED,!allSelected);
                mDataTrackDbHelper.updateData(values);
                Handler handler = new Handler(getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        mAudioItemArrayAdapter.checkAudioItem(-1,!allSelected);
                        mAudioItemArrayAdapter.setAllChecked(!allSelected);
                    }
                });
            }
        });
        thread.start();
    }

    /**
     * Sort list in desired order
     * @param sortBy the field/column to sort by
     * @param sortType the sort type, may be ascendant or descendant
     */
    private void sortBy(String sortBy, int sortType){

        //if no songs, no case sort anything
        if(mAudioItemList.size() == 0 ){
            showSnackBar(Snackbar.LENGTH_SHORT, getString(R.string.no_items), NO_ID);
            return;
        }

        //wait for sorting while correction task is running
        if(ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning()){
            showSnackBar(Snackbar.LENGTH_LONG,getString(R.string.processing_task),-1);
            return;
        }

        mRecyclerView.stopScroll();
        sSorter.setSortParams(sortBy, sortType);
        Collections.sort(mAudioItemList, sSorter);
        mAudioItemArrayAdapter.notifyDataSetChanged();
    }



    /**
     * This method starts a correction for a single item_list
     * @param view the root view pressed, in this card view the root
     * @param position the position of item in list
     * @throws IOException
     * @throws InterruptedException
     */
    private void correctSong(View view, final int position) throws IOException, InterruptedException {
        boolean isServiceRunning = ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning();
        if(isServiceRunning){
            Toast toast = Toast.makeText(getApplicationContext(), R.string.processing_task, Toast.LENGTH_LONG);
            View v = toast.getView();
            TextView text = v.findViewById(android.R.id.message);
            text.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.grey_900));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                text.setTextAppearance(R.style.CustomToast);
            }
            else {
                text.setTextAppearance(getApplicationContext(),R.style.CustomToast);
            }
            v.setBackground(ContextCompat.getDrawable(getApplicationContext(), R.drawable.background_custom_toast) );
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();


            return;
        }

        final String absolutePath = (String) view.findViewById(R.id.absolute_path).getTag();
        final View getView = view.findViewById(R.id.coverArt);

        final AudioItem audioItem = mAudioItemArrayAdapter.getAudioItemByPosition(position);

        //check if audio file can be accessed
        /*boolean canBeRead = AudioItem.checkFileIntegrity(absolutePath);
        if(!canBeRead){
            showConfirmationDialog(position,audioItem);
            return;
        }*/

        //wait until service finish correction to this track
        if(ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning()){
            showSnackBar(Snackbar.LENGTH_SHORT, getString(R.string.processing_task), NO_ID);
            return;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.correction_mode)).setMessage(getString(R.string.select_correction_mode) + " " + AudioItem.getFilename(absolutePath))
                .setNeutralButton(getString(R.string.manual), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onClickCoverArt(getView, position, Constants.CorrectionModes.MANUAL);
                    }
                })
                .setNegativeButton(getString(R.string.semiautomatic), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {


                        onClickCoverArt(getView, position, Constants.CorrectionModes.SEMI_AUTOMATIC);

                    }
                })
                .setPositiveButton(getString(R.string.automatic), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    performTrackId(audioItem.getId());
                    }
                });
        final AlertDialog dialog =  builder.create();
        dialog.show();
    }

    private void performTrackId(long audioItemId){
        int canContinue = allowExecute(getApplicationContext());
        if(canContinue != 0) {
            setSnackBarMessage(canContinue);
            return;
        }

        registerReceivers();
        Intent intent = new Intent(this, FixerTrackService.class);

        if(Settings.BACKGROUND_CORRECTION)
            intent.putExtra(Constants.Actions.ACTION_SHOW_NOTIFICATION, true);

        intent.putExtra(Constants.Activities.FROM_EDIT_MODE, false);
        intent.putExtra(Constants.MEDIA_STORE_ID, audioItemId);
        startService(intent);
        mFloatingActionButtonStart.hide();
        mFloatingActionButtonStop.show();
    }

    /**
     * Shows dialog that file can be accessed
     * and message of possible cause
     * @param position the position of item in list
     * @param audioItem audio item corresponding to this view
     */
    private void showConfirmationDialog(final int position, final AudioItem audioItem){

        String msg = getString(R.string.file_error);
        String title = getString(R.string.title_dialog_file_error);

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.setPositiveButton("Si", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mAudioItemList.remove(audioItem);
                mAudioItemArrayAdapter.notifyItemRemoved(position);
                updateNumberItems();
            }
        });


        builder.setTitle(title).setMessage(msg);

        final AlertDialog dialog =  builder.create();
        dialog.setCancelable(true);
        dialog.show();
    }


    /**
     * Opens new activity showing up the details from current audio item list pressed
     * @param view the view pressed, may be the root view or any child
     * @param position the position of item in list
     * @param mode indicates what mode of correction execute when enters to TrackDetailsActivity
     */
    public void onClickCoverArt(View view, int position, int mode){

        boolean isServiceRunning = ServiceHelper.withContext(getApplicationContext()).withService(FixerTrackService.CLASS_NAME).isServiceRunning();
        if(isServiceRunning){
            Toast toast = Toast.makeText(getApplicationContext(), R.string.processing_task, Toast.LENGTH_LONG);
            View v = toast.getView();
            TextView text = v.findViewById(android.R.id.message);
            text.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.grey_900));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                text.setTextAppearance(R.style.CustomToast);
            }
            else {
                text.setTextAppearance(getApplicationContext(),R.style.CustomToast);
            }
            v.setBackground(ContextCompat.getDrawable(getApplicationContext(), R.drawable.background_custom_toast) );
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();


            return;
        }

        AudioItem audioItem = mAudioItemArrayAdapter.getAudioItemByPosition(position);
        String path = audioItem.getAbsolutePath();

        /*if(!AudioItem.checkFileIntegrity(path)){
            showConfirmationDialog(position,audioItem);
            return;
        }*/

        if(audioItem.isProcessing()){
            showSnackBar(Snackbar.LENGTH_LONG, getString(R.string.current_file_processing),NO_ID);
            return;
        }

        Intent intent = new Intent(this, TrackDetailsActivity.class);
        intent.putExtra(Constants.POSITION, position);
        intent.putExtra(Constants.MEDIA_STORE_ID, audioItem.getId());
        intent.putExtra(Constants.CorrectionModes.MODE, mode);
        /*ActivityOptionsCompat options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                MainActivity.this,
                view,
                ViewCompat.getTransitionName(view));

        startActivity(intent, options.toBundle());*/

        startActivity(intent);
    }

    /**
     * This method marks as true selected column in database
     * and checks the audioitem checkbox
     * @param id the id generated for this item when DB was created
     * @param view checkbox that was checked or unchecked
     * @param position the position of item in list
     */
    private void checkItem(final long id, View view, final int position){
        AudioItem audioItem = mAudioItemArrayAdapter.getAudioItemByIdOrPath(id);
        Log.d("mMenuItemPath", audioItem.getAbsolutePath());

        /*if(!AudioItem.checkFileIntegrity(audioItem.getAbsolutePath())){
            showConfirmationDialog(position, audioItem);
            return;
        }*/

        boolean checked = audioItem.isChecked();

        final ContentValues checkedValue = new ContentValues();
        checkedValue.put(TrackContract.TrackData.IS_SELECTED, !checked);
        mAudioItemArrayAdapter.checkAudioItem(id, !checked);

        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                mDataTrackDbHelper.updateData(id, checkedValue);
            }
        });
        thread.start();
    }

    /**
     * Executes the scan task
     * to seek supported audio files
     * inside smartphone memory
     */
    private void executeScan(){
        if(mRecyclerView.getAdapter() == null)
            mRecyclerView.setAdapter(mAudioItemArrayAdapter);
        //if previously was granted the permission and our database has data
        //dont' clear that data, only read it instead.
        int taskType = DataTrackDbHelper.existDatabase(getApplicationContext()) && mDataTrackDbHelper.getCount() > 0 ? READ_FROM_DATABASE : CREATE_DATABASE;
        mAsyncFileReader = new AsyncFileReader(taskType, this);
        mAsyncFileReader.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    }


    /**
     * Request permission indicate in input parameter
     * @param permission
     */
    private void requestPermission(String permission) {

        if(permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)){
            //No permission? then request it
            if (ContextCompat.checkSelfPermission(getApplicationContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{permission}, RequiredPermissions.WRITE_EXTERNAL_STORAGE_PERMISSION);
            }
            else {
                executeScan();
            }
        }
    }

    /**
     * Shows a dialog to user
     * to confirm cancelling task
     */
    private void stopTask(){

        final AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(R.string.cancelling).setMessage(R.string.cancel_task)
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                })
                .setPositiveButton("Si", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        //stops service, and sets starting state to FAB
                        Intent requestStopService = new Intent(MainActivity.this, FixerTrackService.class);
                        requestStopService.setAction(Constants.Actions.ACTION_STOP_SERVICE);
                        requestStopService.putExtra(Constants.Actions.ACTION_STOP_SERVICE, Constants.StopsReasons.CANCEL_TASK);
                        startService(requestStopService);

                        finishTaskByUser();
                        MainActivity.this.mFloatingActionButtonStop.hide();
                        MainActivity.this.mFloatingActionButtonStart.show();
                    }
                });
        final AlertDialog dialog =  builder.create();
        dialog.setCancelable(false);
        dialog.show();
    }

    /**
     * Allows to register filters to handle
     * only certain actions sent by FixerTrackService
     */
    private void registerReceivers(){
        //register filters to listen for response from FixerTrackService
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionDone);
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionCancel);
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionCompleteTask);
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionFail);
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionSetAudioProcessing);
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionNotFound);
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionConnectionLost);
        mLocalBroadcastManager.registerReceiver(mReceiver, mFilterActionErrorApi);

    }

    /**
     * Stops service, and sets starting state to FAB
     */
    private void finishTaskByUser(){
        //if correction task is cancelled by user
        //set processing state to false of every item
        mAudioItemArrayAdapter.cancelProcessing();
        //once FixerTrackService has finished, we need to deregister
        //receiver to save battery
        mLocalBroadcastManager.unregisterReceiver(mReceiver);

        MainActivity.this.mFloatingActionButtonStop.hide();
        MainActivity.this.mFloatingActionButtonStart.show();
    }

    /**
     * Sets a message to show in snackbar
     * @param reason numeric code of cause
     */
    private void setSnackBarMessage(int reason){
        String msg = "";
        switch (reason){
            case Constants.Conditions.NO_INTERNET_CONNECTION:
                msg = getString(R.string.no_internet_connection_automatic_mode);
                break;
            case MainActivity.PROCESSING_TASK:
                msg = getString(R.string.processing_task);
                break;
            case Constants.Conditions.NO_INITIALIZED_API:
                msg = getString(R.string.initializing_recognition_api);
                break;
        }
        showSnackBar(Snackbar.LENGTH_SHORT, msg,-1);
    }

    /**
     * Save to shared preferences the access files permission
     */
    private void setFilePermissionGranted(){
        SharedPreferences sharedPreferences = getSharedPreferences(Constants.Application.FULL_QUALIFIED_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("accessFilesPermission", true);
        editor.apply();
        RequiredPermissions.ACCESS_GRANTED_FILES = true;
        editor = null;
        sharedPreferences = null;

    }

    /**
     * Indicates to user why is necessary this permission
     */
    private void showRequestPermissionReason(){
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.title_dialog_permision).setMessage(R.string.explanation_permission_access_files);
        builder.setPositiveButton(R.string.ok_button, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        });
        builder.setNegativeButton(R.string.cancel_button, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                //if user cancelled permission request,
                //show a message indicating that no songs were found
                mSearchAgainMessageTextView.setVisibility(View.VISIBLE);
                mSearchAgainMessageTextView.setText(R.string.swipe_up_search);
                mSwipeRefreshLayout.setRefreshing(false);
            }
        });
        final AlertDialog dialog =  builder.create();
        dialog.setCancelable(false);
        dialog.show();
    }

    /**
     * Sets new tags (if were found) to current audio item
     * processed and update UI
     * @param newAudioItem AudioItem object sent by FixerTrackService, maybe null
     * @param processedId the id sent by FixerTrackService, default value is -1 if no id was sent
     */
    private void setNewItemValues(@Nullable AudioItem newAudioItem, long processedId) {
        Log.d("setNewItemValues",(newAudioItem == null)+"");
        //no results were found if newAudioItem is null
        boolean newAudioItemFound = (newAudioItem != null);
        long id = newAudioItemFound ? newAudioItem.getId() : processedId;
        //get current item by id received and set its processing and check state to false
        AudioItem actualAudioItem = mAudioItemArrayAdapter.getAudioItemByIdOrPath(id);

        //check if actual audio item exist in list yet
        if(actualAudioItem != null) {
            //if new tags were found, extract their values from audio item received from service
            //and sets to actual audio item
            if(newAudioItemFound){
                if(!newAudioItem.getTitle().isEmpty()) {
                    actualAudioItem.setTitle(newAudioItem.getTitle());
                }
                if(!newAudioItem.getArtist().isEmpty()) {
                    actualAudioItem.setArtist(newAudioItem.getArtist());
                }
                if(!newAudioItem.getAlbum().isEmpty()) {
                    actualAudioItem.setAlbum(newAudioItem.getAlbum());
                }

                actualAudioItem.setAbsolutePath(newAudioItem.getAbsolutePath());
                actualAudioItem.setStatus(newAudioItem.getStatus());
            }
            //if not only set status to show an icon to indicate
            //that no tags were found
            else {
                actualAudioItem.setStatus(AudioItem.STATUS_NO_TAGS_FOUND);
            }

            //update UI status of audio item
            actualAudioItem.setChecked(false);
            actualAudioItem.setProcessing(false);
            mAudioItemArrayAdapter.notifyItemChanged(actualAudioItem.getPosition());
            //reset position because can change when we reorder the list
            actualAudioItem.setPosition(-1);
        }
    }

    /**
     * Set its processing state to true and update UI
     * to indicate which audio item is currently processing
     * @param id the id sent by FixerTrackService
     */
    private void setProcessingAudioItem(long id){
        AudioItem audioItem = mAudioItemArrayAdapter.getAudioItemByIdOrPath(id);
        if(audioItem != null) {
            audioItem.setProcessing(true);
            mRecyclerView.scrollToPosition(audioItem.getPosition());
            mAudioItemArrayAdapter.notifyItemChanged(audioItem.getPosition());
            audioItem.setPosition(-1);
        }
    }

    /**
     * Updates number items shown in action bar
     * when are detected
     */
    private void updateNumberItems(){
        if(mAudioItemList != null){
            mActionBar.setTitle(mAudioItemList.size() + " " +getString(R.string.tracks));
        }
    }

    /**
     * Handles responses from {@link FixerTrackService}
     * @param intent
     */
    @Override
    public void onResponse(Intent intent) {
        //get action and handle it
        String action = intent.getAction();
        Log.d(TAG, action);
        switch (action) {
            case Constants.GnServiceActions.ACTION_API_INITIALIZED:
                    if(mAudioItemList.size() > 0 ) {
                        if(mAudioItemList != null && !mAudioItemList.isEmpty())
                           showSnackBar(Snackbar.LENGTH_SHORT, getString(R.string.api_initialized), NO_ID);
                    }
                break;
            case Constants.Actions.ACTION_NOT_FOUND:
                    setNewItemValues(null, intent.getLongExtra(Constants.MEDIA_STORE_ID,-1));
                break;
            case Constants.Actions.ACTION_FAIL:
            case Constants.Actions.ACTION_DONE:
                    AudioItem audioItem = intent.getParcelableExtra(Constants.AUDIO_ITEM);
                    setNewItemValues(audioItem, audioItem.getId());
                break;
            case Constants.Actions.ACTION_SET_AUDIOITEM_PROCESSING:
                    setProcessingAudioItem(intent.getLongExtra(Constants.MEDIA_STORE_ID,NO_ID));
                break;
            case Constants.Actions.ACTION_CONNECTION_LOST:
            case Constants.Actions.ACTION_CANCEL_TASK:
            case Constants.Actions.ACTION_COMPLETE_TASK:
                    finishTaskByUser();
                    mLocalBroadcastManager.unregisterReceiver(mReceiver);
                break;
            case Constants.Actions.ACTION_REQUEST_UPDATE_LIST:
                    if(Settings.SETTING_AUTO_UPDATE_LIST) {
                        mAsyncFileReader = new AsyncFileReader(RE_SCAN, this);
                        mAsyncFileReader.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
                    }
                    else {
                        showSnackBar(Snackbar.LENGTH_INDEFINITE, getString(R.string.storage_has_change), UPDATE_LIST);
                    }
                break;
            default:
                    finishTaskByUser();
                    String msg = intent.getStringExtra(Constants.GnServiceActions.API_ERROR);
                    showSnackBar(Snackbar.LENGTH_LONG, getString(R.string.api_error) + " " + msg, NO_ID);
                    mLocalBroadcastManager.unregisterReceiver(mReceiver);
                break;
        }

    }

    /**
     * This class request reads information of audio files from MediaStore,
     * then creates the initial DB when app
     * is used by first time; next openings of app it
     * reads the songs from this database instead of MediaStore
     */
    public static class AsyncFileReader extends AsyncTask<Void, AudioItem, Void> {
        //Are we reading from our DB or MediaStore?
        private int taskType;
        //data retrieved from DB
        private Cursor data;
        //how many audio files were added or removed
        //from your smartphone
        private int added = 0;
        private int removed = 0;
        private boolean mMediaScanCompleted = false;
        private WeakReference<MainActivity> mWeakRef;

        AsyncFileReader(int codeTaskType, MainActivity mainActivity){
            mWeakRef = new WeakReference<>(mainActivity);
            taskType = codeTaskType;
        }

        @Override
        protected void onPreExecute() {
            //check if a previous reading of files
            //could not be successful completed, in this case
            //clear DB and re read data from Media Store

            SharedPreferences sharedPreferences = mWeakRef.get().getSharedPreferences(Constants.Application.FULL_QUALIFIED_NAME, Context.MODE_PRIVATE);
            mMediaScanCompleted = sharedPreferences.getBoolean(Constants.COMPLETE_READ,false);
            if(!mMediaScanCompleted){
                taskType = CREATE_DATABASE;
                mWeakRef.get().showSnackBar(Snackbar.LENGTH_SHORT, mWeakRef.get().getString(R.string.getting_data),NO_ID);
                mWeakRef.get().mSearchAgainMessageTextView.setVisibility(View.GONE);
            }

            //this method is invoked before
            //our background task begins, often
            //here is where we show for example
            //a progress bar.
            mWeakRef.get().mSwipeRefreshLayout.setRefreshing(true);
            sIsGettingData = true;
            if(mWeakRef.get().mSearchViewWidget != null){
                mWeakRef.get().mSearchViewWidget.setVisibility(View.GONE);
            }

        }

        @Override
        protected Void doInBackground(Void... voids) {
            //make sure when is first use, clearing
            //database to avoid any problem, in case
            //there have had a unsuccessful previous
            //creation of DB

            switch (taskType){
                //If we are updating for new elements added
                case RE_SCAN:
                    rescanAndUpdateList();
                    removeUnusedItems();
                    break;
                //if database does not exist or is first use of app
                case CREATE_DATABASE:
                    mWeakRef.get().mDataTrackDbHelper.clearDb();
                    createNewTable();
                    break;
                //if we are reading data from database (this means later app openings)
                case READ_FROM_DATABASE:
                    removeUnusedItems();
                    readFromDatabase();
                    break;
            }

            //close cursor to release this resource
            //there are a limit of cursors open at the same time
            //so make sure to release them.
            if(this.data != null) {
                data.close();
                data = null;
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(AudioItem... audioItems) {
            //this method runs on UI thread,
            //here we are updating the UI.
            //is not necessary call its super method
            //because is an emtpy method

            //adds the item to top of list
            if(taskType == RE_SCAN){
                if(audioItems[0].getId() == -1) {
                    mWeakRef.get().mAudioItemList.remove(audioItems[0]);
                    mWeakRef.get().mAudioItemArrayAdapter.notifyItemRemoved(audioItems[0].getPosition());
                }
                else {
                    mWeakRef.get().mAudioItemList.add(0, audioItems[0]);
                    mWeakRef.get().mAudioItemArrayAdapter.notifyItemInserted(0);
                }
            }
            else {
                mWeakRef.get().mAudioItemList.add(audioItems[0]);
                mWeakRef.get().mAudioItemArrayAdapter.notifyItemInserted(mWeakRef.get().mAudioItemList.size()-1);
            }

            //updates title of action bar
            mWeakRef.get().updateNumberItems();
        }

        @Override
        protected void onPostExecute(Void result) {
            //when doInBackground finishes, this callback
            //is executed. Here is where we hide, for example,
            //a progress bar.
            mWeakRef.get().mAsyncFileReader = null;
            //is not necessary call its super method
            //because is an emtpy method

            mWeakRef.get().mSwipeRefreshLayout.setRefreshing(false);
            mWeakRef.get().mSwipeRefreshLayout.setEnabled(true);
            sIsGettingData = false;

            //while we are creating the DB, if user closes the app
            //COMPLETED_READ can not be successful, so
            //next time app starts will try to create again the database
            //until success
            if(taskType == CREATE_DATABASE) {
                SharedPreferences sharedPreferences = mWeakRef.get().getSharedPreferences(Constants.Application.FULL_QUALIFIED_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putBoolean(Constants.COMPLETE_READ, true);
                editor.apply();
            }

            //there are not songs?
            if(mWeakRef.get().mAudioItemList.size() == 0){
                mWeakRef.get().mSearchAgainMessageTextView.setText(mWeakRef.get().getString(R.string.no_items_found));
                mWeakRef.get().mSearchAgainMessageTextView.setVisibility(View.VISIBLE);

                return;
            }

            if(removed > 0){
                mWeakRef.get().showSnackBar(Toast.LENGTH_SHORT,removed + " " + mWeakRef.get().getString(R.string.removed_inexistent),NO_ID);
            }

            if(added > 0 ){
                mWeakRef.get().showSnackBar(Toast.LENGTH_SHORT,added + " " + mWeakRef.get().getString(R.string.new_files_found),NO_ID);
                mWeakRef.get().mRecyclerView.smoothScrollToPosition(0);
            }

            if(mWeakRef.get().mSearchViewWidget != null){
                mWeakRef.get().mSearchViewWidget.setVisibility(View.VISIBLE);
            }

            mWeakRef.get().updateNumberItems();

            //if audio files were found, then show
            //float action button that executes main task.
            //if no audio files were found, there is no case
            //to show this button because there will not have
            //anything to process.
            mWeakRef.get().mFloatingActionButtonStart.show();
            //Check if exist SD to show instructions about how to activate permission
            //to write to SD
            boolean isPresentSD = StorageHelper.getInstance(mWeakRef.get().getApplicationContext()).isPresentRemovableStorage();
            if(isPresentSD){
                if((Constants.URI_SD_CARD == null || !Settings.ENABLE_SD_CARD_ACCESS))
                    mWeakRef.get().startActivity(new Intent(mWeakRef.get(), TransparentActivity.class));
            }
            else {
                //Revoke permission to write to SD card and remove URI from shared preferences.
                if(Constants.URI_SD_CARD != null){
                    int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    mWeakRef.get().getContentResolver().releasePersistableUriPermission(Constants.URI_SD_CARD, takeFlags);
                    Constants.URI_SD_CARD = null;
                    Settings.ENABLE_SD_CARD_ACCESS = false;
                }

                SharedPreferences sharedPreferences = mWeakRef.get().getSharedPreferences(Constants.Application.FULL_QUALIFIED_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.remove(Constants.URI_TREE);
                editor.apply();
            }
            mWeakRef.clear();
            mWeakRef = null;
            added = 0;
            removed = 0;
            System.gc();
        }

        @Override
        public void onCancelled(){
            super.onCancelled();
            //this method will be invoked instead of
            //onPostExecute if AsyncTask is cancelled
            mWeakRef.get().mAsyncFileReader = null;
            sIsGettingData = false;
            mWeakRef.get().mSwipeRefreshLayout.setEnabled(true);
            mWeakRef.get().mSwipeRefreshLayout.setRefreshing(false);
            mWeakRef.get().updateNumberItems();
            if(mWeakRef.get().mSearchViewWidget != null){
                mWeakRef.get().mSearchViewWidget.setVisibility(View.VISIBLE);
            }
            //save state that could not be completed the data loading from songs
            SharedPreferences sharedPreferences = mWeakRef.get().getSharedPreferences(Constants.Application.FULL_QUALIFIED_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor =  sharedPreferences.edit();
            mWeakRef.get().mSearchAgainMessageTextView.setText(mWeakRef.get().getString(R.string.no_items_found));
            mWeakRef.get().mSearchAgainMessageTextView.setVisibility(View.VISIBLE);
            editor.putBoolean(Constants.COMPLETE_READ, false);
            editor.apply();
            editor = null;
            sharedPreferences = null;
            mWeakRef.clear();
            mWeakRef = null;
            added = 0;
            removed = 0;
            System.gc();
        }

        /**
         * This method gets data of every song
         * from media store,
         * @return A cursor with data retrieved
         */
        private Cursor getDataFromDevice() {

            //Select all music
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

            //Columns to retrieve
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.AlbumColumns.ALBUM ,
                    MediaStore.Audio.Media.DATA // absolute path to audio file
            };

            //get data from content provider
            //the last parameter sorts the data alphanumerically by the "DATA" column in ascendant mode
            return mWeakRef.get().getApplicationContext().getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    Sort.setDefaultOrder());
        }

        /**
         * Updates list by adding any new audio file
         * detected in smartphone
         */
        private void rescanAndUpdateList(){
            data = getDataFromDevice();
            while(data.moveToNext()){
                boolean existInTable = mWeakRef.get().mDataTrackDbHelper.existInDatabase(data.getInt(0));

                if(!existInTable){
                    createAndAddAudioItem();
                    added++;
                }
            }

            if(this.data != null) {
                data.close();
                data = null;
            }
        }

        /**
         * Updates list by removing those items
         * that have changed its path, removed
         * or deleted.
         */
        private void removeUnusedItems(){
            for(int pos = mWeakRef.get().mAudioItemList.size() -1; pos >= 0 ; --pos){
                AudioItem audioItem = mWeakRef.get().mAudioItemList.get(pos);
                File file = new File(audioItem.getAbsolutePath());
                if (!file.exists()) {
                    mWeakRef.get().mDataTrackDbHelper.removeItem(audioItem.getId(), TrackContract.TrackData.TABLE_NAME);
                    publishProgress(audioItem.setId(-1).setPosition(pos));
                    removed++;
                    Log.d("removed", removed+"");
                    //pos--;
                }
                file = null;
            }
        }


        /**
         * Creates the DB that app uses
         * to store the state of every song,
         * generally it only runs the first time
         * the app is executed, or when DB
         * could not be created at any previous app open.
         */
        private void createNewTable(){
            data = getDataFromDevice();
            if(data.moveToFirst()) {
                do {
                    createAndAddAudioItem();
                }
                while (data.moveToNext());
            }
        }

        /**
         * When app DB is generated, now it reads
         * info about songs from here, not from MediaStore
         */
        private void readFromDatabase(){

            data = mWeakRef.get().mDataTrackDbHelper.getDataFromDB(Sort.setDefaultOrder());
            //retrieved null data means there are no elements, so our database
            int dataLength = data != null ? data.getCount() : 0;
            if (dataLength > 0) {
                while (data.moveToNext()) {
                    int _id = data.getInt(data.getColumnIndexOrThrow(TrackContract.TrackData.MEDIASTORE_ID));
                    String title = data.getString(data.getColumnIndexOrThrow(TrackContract.TrackData.TITLE));
                    String artist = data.getString(data.getColumnIndexOrThrow(TrackContract.TrackData.ARTIST));
                    String album = data.getString(data.getColumnIndexOrThrow(TrackContract.TrackData.ALBUM));
                    String fullpath = data.getString(data.getColumnIndexOrThrow(TrackContract.TrackData.DATA));
                    boolean isChecked = data.getInt(data.getColumnIndexOrThrow(TrackContract.TrackData.IS_SELECTED)) != 0;
                    int status = data.getInt(data.getColumnIndexOrThrow(TrackContract.TrackData.STATUS));
                    boolean isProcessing = data.getInt(data.getColumnIndexOrThrow(TrackContract.TrackData.IS_PROCESSING)) != 0;

                    AudioItem audioItem = new AudioItem();
                    audioItem.setId(_id).
                            setTitle(title).
                            setArtist(artist).
                            setAlbum(album).
                            setAbsolutePath(fullpath).
                            setChecked(isChecked).
                            setStatus(status).
                            setProcessing(isProcessing);

                    publishProgress(audioItem);

                }//end while
            }//end if
        }


        /**
         * Creates the AudioItem object with the info
         * song, then calls publish progress passing
         * this object and adding to list
         */
        private void createAndAddAudioItem(){

            if(isCancelled())
                return;

            int mediaStoreId = data.getInt(0);//mediastore id
            String title = data.getString(1);
            String artist = data.getString(2);
            String album = data.getString(3);
            String path = data.getString(4);
            String fullPath = Uri.parse(path).toString(); //MediaStore.Audio.Media.DATA column is the file mMenuItemPath

            ContentValues values = new ContentValues();
            values.put(TrackContract.TrackData.MEDIASTORE_ID,mediaStoreId);
            values.put(TrackContract.TrackData.TITLE, title);
            values.put(TrackContract.TrackData.ARTIST, artist);
            values.put(TrackContract.TrackData.ALBUM, album);
            values.put(TrackContract.TrackData.DATA, fullPath);

            AudioItem audioItem = new AudioItem();

            //we need to set id in audio item_list because all operations
            //we do, relay in this id,
            //so when we save row to DB
            //it returns its id as a result
            mWeakRef.get().mDataTrackDbHelper.insertItem(values, TrackContract.TrackData.TABLE_NAME);
            audioItem.setId(mediaStoreId).setTitle(title).setArtist(artist).setAlbum(album).setAbsolutePath(fullPath);
            if(isCancelled())
                return;
            publishProgress(audioItem);
            values.clear();
            values = null;
        }

    }

    /**
     * Sets default order for querying our DB
     */

    public static class Sort{
        public static String setDefaultOrder(){

            switch (Settings.SETTING_SORT){
                case R.id.path_asc:
                case 0:

                    return MediaStore.Audio.AudioColumns.DATA + " COLLATE NOCASE ASC";
                case R.id.path_desc:
                case 1:

                    return MediaStore.Audio.AudioColumns.DATA + " COLLATE NOCASE DESC";
                case R.id.title_asc:

                    return MediaStore.Audio.AudioColumns.TITLE + " COLLATE NOCASE ASC";
                case R.id.title_desc:

                    return MediaStore.Audio.AudioColumns.TITLE + " COLLATE NOCASE DESC";
                case R.id.artist_asc:

                    return MediaStore.Audio.AudioColumns.ARTIST + " COLLATE NOCASE ASC";
                case R.id.artist_desc:

                    return MediaStore.Audio.AudioColumns.ARTIST + " COLLATE NOCASE DESC";
                case R.id.album_asc:

                    return MediaStore.Audio.AudioColumns.ALBUM + " COLLATE NOCASE ASC";
                case R.id.album_desc:

                    return MediaStore.Audio.AudioColumns.ALBUM + " COLLATE NOCASE DESC";
                default:

                    return MediaStore.Audio.AudioColumns.DATA + " COLLATE NOCASE ASC";

            }
        }
    }

}
