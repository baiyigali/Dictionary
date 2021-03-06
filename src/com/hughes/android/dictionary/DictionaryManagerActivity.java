// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.hughes.android.dictionary;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.SearchView.OnQueryTextListener;
import android.support.v7.widget.Toolbar;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.hughes.android.dictionary.DictionaryInfo.IndexInfo;
import com.hughes.android.util.IntentLauncher;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

// Right-click:
//  Delete, move to top.

public class DictionaryManagerActivity extends ActionBarActivity {

    static final String LOG = "QuickDic";
    static boolean blockAutoLaunch = false;

    private ListView listView;
    private ListView getListView() {
        if (listView == null) {
            listView = (ListView)findViewById(android.R.id.list);
        }
        return listView;
    }
    private void setListAdapter(ListAdapter adapter) {
        getListView().setAdapter(adapter);
    }
    private ListAdapter getListAdapter() {
        return getListView().getAdapter();
    }

    // For DownloadManager bug workaround
    private Set<Long> finishedDownloadIds = new HashSet<Long>();

    DictionaryApplication application;

    SearchView filterSearchView;
    ToggleButton showDownloadable;

    LinearLayout dictionariesOnDeviceHeaderRow;
    LinearLayout downloadableDictionariesHeaderRow;

    Handler uiHandler;

    Runnable dictionaryUpdater = new Runnable() {
        @Override
        public void run() {
            if (uiHandler == null) {
                return;
            }
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    setMyListAdapater();
                }
            });
        }
    };

    final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public synchronized void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                final long downloadId = intent.getLongExtra(
                                            DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                if (finishedDownloadIds.contains(downloadId)) return; // ignore double notifications
                final DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                final DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                final Cursor cursor = downloadManager.query(query);

                if (cursor == null || !cursor.moveToFirst()) {
                    Log.e(LOG, "Couldn't find download.");
                    return;
                }

                final String dest = cursor
                                    .getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                final int status = cursor
                                   .getInt(cursor
                                           .getColumnIndex(DownloadManager.COLUMN_STATUS));
                if (DownloadManager.STATUS_SUCCESSFUL != status) {
                    final int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
                    Log.w(LOG,
                          "Download failed: status=" + status +
                          ", reason=" + reason);
                    String msg = Integer.toString(reason);
                    switch (reason) {
                    case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                        msg = "File exists";
                        break;
                    case DownloadManager.ERROR_FILE_ERROR:
                        msg = "File error";
                        break;
                    case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                        msg = "Not enough space";
                        break;
                    }
                    new AlertDialog.Builder(context).setTitle(getString(R.string.error)).setMessage(getString(R.string.downloadFailed, msg)).setNeutralButton("Close", null).show();
                    return;
                }

                Log.w(LOG, "Download finished: " + dest + " Id: " + downloadId);
                Toast.makeText(context, getString(R.string.unzippingDictionary, dest),
                               Toast.LENGTH_LONG).show();

                if (unzipInstall(context, Uri.parse(dest), dest)) {
                    finishedDownloadIds.add(downloadId);
                    Log.w(LOG, "Unzipping finished: " + dest + " Id: " + downloadId);
                }
            }
        }
    };

    private boolean unzipInstall(Context context, Uri zipUri, String dest) {
        File localZipFile = null;
        InputStream zipFileStream = null;
        ZipInputStream zipFile = null;
        FileOutputStream zipOut = null;
        boolean result = false;
        try {
            if (zipUri.getScheme().equals("content")) {
                zipFileStream = context.getContentResolver().openInputStream(zipUri);
                localZipFile = null;
            } else {
                localZipFile = new File(zipUri.getPath());
                zipFileStream = new FileInputStream(localZipFile);
            }
            zipFile = new ZipInputStream(new BufferedInputStream(zipFileStream));
            final ZipEntry zipEntry = zipFile.getNextEntry();
            Log.d(LOG, "Unzipping entry: " + zipEntry.getName());
            File targetFile = new File(application.getDictDir(), zipEntry.getName());
            if (targetFile.exists()) {
                targetFile.renameTo(new File(targetFile.getAbsolutePath().replace(".quickdic", ".bak.quickdic")));
                targetFile = new File(application.getDictDir(), zipEntry.getName());
            }
            zipOut = new FileOutputStream(targetFile);
            copyStream(zipFile, zipOut);
            application.backgroundUpdateDictionaries(dictionaryUpdater);
            Toast.makeText(context, getString(R.string.installationFinished, dest),
                           Toast.LENGTH_LONG).show();
            result = true;
        } catch (Exception e) {
            String msg = getString(R.string.unzippingFailed, dest);
            File dir = application.getDictDir();
            if (!dir.canWrite() || !application.checkFileCreate(dir)) {
                msg = getString(R.string.notWritable, dir.getAbsolutePath());
            }
            new AlertDialog.Builder(context).setTitle(getString(R.string.error)).setMessage(msg).setNeutralButton("Close", null).show();
            Log.e(LOG, "Failed to unzip.", e);
        } finally {
            try {
                if (zipOut != null) zipOut.close();
            } catch (IOException e) {}
            try {
                if (zipFile != null) zipFile.close();
            } catch (IOException e) {}
            try {
                if (zipFileStream != null) zipFileStream.close();
            } catch (IOException e) {}
            if (localZipFile != null) localZipFile.delete();
        }
        return result;
    }

    public static Intent getLaunchIntent(Context c) {
        final Intent intent = new Intent(c, DictionaryManagerActivity.class);
        intent.putExtra(C.CAN_AUTO_LAUNCH_DICT, false);
        return intent;
    }

    public void readableCheckAndError(boolean requestPermission) {
        final File dictDir = application.getDictDir();
        if (dictDir.canRead() && dictDir.canExecute()) return;
        blockAutoLaunch = true;
        if (requestPermission &&
                ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                                              new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                                                      Manifest.permission.WRITE_EXTERNAL_STORAGE
                                                           }, 0);
            return;
        }
        blockAutoLaunch = true;

        AlertDialog.Builder builder = new AlertDialog.Builder(getListView().getContext());
        builder.setTitle(getString(R.string.error));
        builder.setMessage(getString(
                               R.string.unableToReadDictionaryDir,
                               dictDir.getAbsolutePath(),
                               Environment.getExternalStorageDirectory()));
        builder.setNeutralButton("Close", null);
        builder.create().show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        readableCheckAndError(false);

        application.backgroundUpdateDictionaries(dictionaryUpdater);

        setMyListAdapater();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        DictionaryApplication.INSTANCE.init(getApplicationContext());
        application = DictionaryApplication.INSTANCE;
        // This must be first, otherwise the action bar doesn't get
        // styled properly.
        setTheme(application.getSelectedTheme().themeId);

        super.onCreate(savedInstanceState);
        Log.d(LOG, "onCreate:" + this);

        setTheme(application.getSelectedTheme().themeId);

        blockAutoLaunch = false;

        // UI init.
        setContentView(R.layout.dictionary_manager_activity);

        dictionariesOnDeviceHeaderRow = (LinearLayout) LayoutInflater.from(
                                            getListView().getContext()).inflate(
                                            R.layout.dictionary_manager_header_row_on_device, getListView(), false);

        downloadableDictionariesHeaderRow = (LinearLayout) LayoutInflater.from(
                                                getListView().getContext()).inflate(
                                                R.layout.dictionary_manager_header_row_downloadable, getListView(), false);

        showDownloadable = (ToggleButton) downloadableDictionariesHeaderRow
                           .findViewById(R.id.hideDownloadable);
        showDownloadable.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                onShowDownloadableChanged();
            }
        });

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final String thanksForUpdatingLatestVersion = getString(R.string.thanksForUpdatingVersion);
        if (!prefs.getString(C.THANKS_FOR_UPDATING_VERSION, "").equals(
                    thanksForUpdatingLatestVersion)) {
            blockAutoLaunch = true;
            startActivity(HtmlDisplayActivity.getWhatsNewLaunchIntent(getApplicationContext()));
            prefs.edit().putString(C.THANKS_FOR_UPDATING_VERSION, thanksForUpdatingLatestVersion)
            .commit();
        }

        registerReceiver(broadcastReceiver, new IntentFilter(
                             DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        setMyListAdapater();
        registerForContextMenu(getListView());
        getListView().setItemsCanFocus(true);

        readableCheckAndError(true);

        onCreateSetupActionBar();

        final Intent intent = getIntent();
        if (intent != null && intent.getAction() != null &&
            intent.getAction().equals(Intent.ACTION_VIEW)) {
            blockAutoLaunch = true;
            Uri uri = intent.getData();
            unzipInstall(this, uri, uri.getLastPathSegment());
        }
    }

    private void onCreateSetupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setDisplayShowHomeEnabled(false);
        actionBar.setDisplayHomeAsUpEnabled(false);

        filterSearchView = new SearchView(getSupportActionBar().getThemedContext());
        filterSearchView.setIconifiedByDefault(false);
        // filterSearchView.setIconified(false); // puts the magnifying glass in
        // the
        // wrong place.
        filterSearchView.setQueryHint(getString(R.string.searchText));
        filterSearchView.setSubmitButtonEnabled(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        filterSearchView.setLayoutParams(lp);
        filterSearchView.setInputType(InputType.TYPE_CLASS_TEXT);
        filterSearchView.setImeOptions(
            EditorInfo.IME_ACTION_DONE |
            EditorInfo.IME_FLAG_NO_EXTRACT_UI |
            // EditorInfo.IME_FLAG_NO_FULLSCREEN | // Requires API
            // 11
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        filterSearchView.setOnQueryTextListener(new OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                filterSearchView.clearFocus();
                return false;
            }

            @Override
            public boolean onQueryTextChange(String filterText) {
                setMyListAdapater();
                return true;
            }
        });
        filterSearchView.setFocusable(true);

        actionBar.setCustomView(filterSearchView);
        actionBar.setDisplayShowCustomEnabled(true);

        // Avoid wasting space on large left inset
        Toolbar tb = (Toolbar)filterSearchView.getParent();
        tb.setContentInsetsRelative(0, 0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(broadcastReceiver);
    }

    private static void copyStream(final InputStream ins, final FileOutputStream outs)
    throws IOException {
        ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 64);
        FileChannel out = outs.getChannel();
        int bytesRead;
        int pos = 0;
        final byte[] bytes = new byte[1024 * 64];
        do {
            bytesRead = ins.read(bytes, pos, bytes.length - pos);
            if (bytesRead != -1) pos += bytesRead;
            if (bytesRead == -1 ? pos != 0 : 2*pos >= bytes.length) {
                buf.put(bytes, 0, pos);
                pos = 0;
                buf.flip();
                while (buf.hasRemaining()) out.write(buf);
                buf.clear();
            }
        } while (bytesRead != -1);
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiHandler = new Handler();
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler = null;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (PreferenceActivity.prefsMightHaveChanged) {
            PreferenceActivity.prefsMightHaveChanged = false;
            finish();
            startActivity(getIntent());
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        showDownloadable.setChecked(prefs.getBoolean(C.SHOW_DOWNLOADABLE, true));

        if (!blockAutoLaunch &&
                getIntent().getBooleanExtra(C.CAN_AUTO_LAUNCH_DICT, true) &&
                prefs.contains(C.DICT_FILE) &&
                prefs.contains(C.INDEX_SHORT_NAME)) {
            Log.d(LOG, "Skipping DictionaryManager, going straight to dictionary.");
            startActivity(DictionaryActivity.getLaunchIntent(getApplicationContext(),
                          new File(prefs.getString(C.DICT_FILE, "")),
                          prefs.getString(C.INDEX_SHORT_NAME, ""),
                          prefs.getString(C.SEARCH_TOKEN, "")));
            finish();
            return;
        }

        // Remove the active dictionary from the prefs so we won't autolaunch
        // next time.
        final Editor editor = prefs.edit();
        editor.remove(C.DICT_FILE);
        editor.remove(C.INDEX_SHORT_NAME);
        editor.remove(C.SEARCH_TOKEN);
        editor.commit();

        application.backgroundUpdateDictionaries(dictionaryUpdater);

        setMyListAdapater();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if ("true".equals(Settings.System.getString(getContentResolver(), "firebase.test.lab")))
        {
            return false; // testing the menu is not very interesting
        }
        final MenuItem sort = menu.add(getString(R.string.sortDicts));
        MenuItemCompat.setShowAsAction(sort, MenuItem.SHOW_AS_ACTION_NEVER);
        sort.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(final MenuItem menuItem) {
                application.sortDictionaries();
                setMyListAdapater();
                return true;
            }
        });

        application.onCreateGlobalOptionsMenu(this, menu);
        return true;
    }

    @Override
    public void onCreateContextMenu(final ContextMenu menu, final View view,
                                    final ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, view, menuInfo);
        Log.d(LOG, "onCreateContextMenu, " + menuInfo);

        final AdapterContextMenuInfo adapterContextMenuInfo =
            (AdapterContextMenuInfo) menuInfo;
        final int position = adapterContextMenuInfo.position;
        final MyListAdapter.Row row = (MyListAdapter.Row) getListAdapter().getItem(position);

        if (row.dictionaryInfo == null) {
            return;
        }

        if (position > 0 && row.onDevice) {
            final android.view.MenuItem moveToTopMenuItem =
                menu.add(R.string.moveToTop);
            moveToTopMenuItem.setOnMenuItemClickListener(new
            android.view.MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    application.moveDictionaryToTop(row.dictionaryInfo);
                    setMyListAdapater();
                    return true;
                }
            });
        }

        if (row.onDevice) {
            final android.view.MenuItem deleteMenuItem = menu.add(R.string.deleteDictionary);
            deleteMenuItem
            .setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    application.deleteDictionary(row.dictionaryInfo);
                    setMyListAdapater();
                    return true;
                }
            });
        }
    }

    private void onShowDownloadableChanged() {
        setMyListAdapater();
        Editor prefs = PreferenceManager.getDefaultSharedPreferences(this).edit();
        prefs.putBoolean(C.SHOW_DOWNLOADABLE, showDownloadable.isChecked());
        prefs.commit();
    }

    class MyListAdapter extends BaseAdapter {

        List<DictionaryInfo> dictionariesOnDevice;
        List<DictionaryInfo> downloadableDictionaries;

        class Row {
            DictionaryInfo dictionaryInfo;
            boolean onDevice;

            private Row(DictionaryInfo dictionaryInfo, boolean onDevice) {
                this.dictionaryInfo = dictionaryInfo;
                this.onDevice = onDevice;
            }
        }

        private MyListAdapter(final String[] filters) {
            dictionariesOnDevice = application.getDictionariesOnDevice(filters);
            if (showDownloadable.isChecked()) {
                downloadableDictionaries = application.getDownloadableDictionaries(filters);
            } else {
                downloadableDictionaries = Collections.emptyList();
            }
        }

        @Override
        public int getCount() {
            return 2 + dictionariesOnDevice.size() + downloadableDictionaries.size();
        }

        @Override
        public Row getItem(int position) {
            if (position == 0) {
                return new Row(null, true);
            }
            position -= 1;

            if (position < dictionariesOnDevice.size()) {
                return new Row(dictionariesOnDevice.get(position), true);
            }
            position -= dictionariesOnDevice.size();

            if (position == 0) {
                return new Row(null, false);
            }
            position -= 1;

            assert position < downloadableDictionaries.size();
            return new Row(downloadableDictionaries.get(position), false);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView instanceof LinearLayout &&
                    convertView != dictionariesOnDeviceHeaderRow &&
                    convertView != downloadableDictionariesHeaderRow) {
                /*
                 * This is done to try to avoid leaking memory that used to
                 * happen on Android 4.0.3
                 */
                ((LinearLayout) convertView).removeAllViews();
            }

            final Row row = getItem(position);

            if (row.onDevice) {
                if (row.dictionaryInfo == null) {
                    return dictionariesOnDeviceHeaderRow;
                }
                return createDictionaryRow(row.dictionaryInfo, parent, true);
            }

            if (row.dictionaryInfo == null) {
                return downloadableDictionariesHeaderRow;
            }
            return createDictionaryRow(row.dictionaryInfo, parent, false);
        }

    }

    private void setMyListAdapater() {
        final String filter = filterSearchView == null ? "" : filterSearchView.getQuery()
                              .toString();
        final String[] filters = filter.trim().toLowerCase().split("(\\s|-)+");
        setListAdapter(new MyListAdapter(filters));
    }

    private View createDictionaryRow(final DictionaryInfo dictionaryInfo,
                                     final ViewGroup parent, boolean canLaunch) {

        View row = LayoutInflater.from(parent.getContext()).inflate(
                       R.layout.dictionary_manager_row, parent, false);
        final TextView name = (TextView) row.findViewById(R.id.dictionaryName);
        final TextView details = (TextView) row.findViewById(R.id.dictionaryDetails);
        name.setText(application.getDictionaryName(dictionaryInfo.uncompressedFilename));

        final boolean updateAvailable = application.updateAvailable(dictionaryInfo);
        final Button downloadButton = (Button) row.findViewById(R.id.downloadButton);
        final DictionaryInfo downloadable = application.getDownloadable(dictionaryInfo.uncompressedFilename);
        boolean broken = false;
        if (!dictionaryInfo.isValid()) {
            broken = true;
            canLaunch = false;
        }
        if (downloadable != null && (!canLaunch || updateAvailable)) {
            downloadButton
            .setText(getString(
                         R.string.downloadButton,
                         downloadable.zipBytes / 1024.0 / 1024.0));
            downloadButton.setMinWidth(application.languageButtonPixels * 3 / 2);
            downloadButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View arg0) {
                    downloadDictionary(downloadable.downloadUrl, downloadable.zipBytes, downloadButton);
                }
            });
        } else {
            downloadButton.setVisibility(View.INVISIBLE);
        }

        LinearLayout buttons = (LinearLayout) row.findViewById(R.id.dictionaryLauncherButtons);
        final List<IndexInfo> sortedIndexInfos = application
                .sortedIndexInfos(dictionaryInfo.indexInfos);
        final StringBuilder builder = new StringBuilder();
        if (updateAvailable) {
            builder.append(getString(R.string.updateAvailable));
        }
        for (IndexInfo indexInfo : sortedIndexInfos) {
            final View button = IsoUtils.INSTANCE.createButton(buttons.getContext(), dictionaryInfo,
                                indexInfo, application.languageButtonPixels);
            buttons.addView(button);

            if (canLaunch) {
                button.setOnClickListener(
                    new IntentLauncher(buttons.getContext(),
                                       DictionaryActivity.getLaunchIntent(getApplicationContext(),
                                               application.getPath(dictionaryInfo.uncompressedFilename),
                                               indexInfo.shortName, "")));

            } else {
                button.setEnabled(false);
                button.setFocusable(false);
            }
            if (builder.length() != 0) {
                builder.append("; ");
            }
            builder.append(getString(R.string.indexInfo, indexInfo.shortName,
                                     indexInfo.mainTokenCount));
        }
        builder.append("; ");
        builder.append(getString(R.string.downloadButton, dictionaryInfo.uncompressedBytes / 1024.0 / 1024.0));
        if (broken) {
            name.setText("Broken: " + application.getDictionaryName(dictionaryInfo.uncompressedFilename));
            builder.append("; Cannot be used, redownload, check hardware/file system");
            // Allow deleting, but cannot open
            row.setLongClickable(true);
        }
        details.setText(builder.toString());

        if (canLaunch) {
            row.setClickable(true);
            row.setOnClickListener(new IntentLauncher(parent.getContext(),
                                   DictionaryActivity.getLaunchIntent(getApplicationContext(),
                                           application.getPath(dictionaryInfo.uncompressedFilename),
                                           dictionaryInfo.indexInfos.get(0).shortName, "")));
            // do not setFocusable, for keyboard navigation
            // offering only the index buttons is better.
            row.setLongClickable(true);
        }
        row.setBackgroundResource(android.R.drawable.menuitem_background);

        return row;
    }

    private synchronized void downloadDictionary(final String downloadUrl, long bytes, Button downloadButton) {
        String destFile;
        try {
            destFile = new File(new URL(downloadUrl).getPath()).getName();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid download URL!", e);
        }
        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        final DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING);
        final Cursor cursor = downloadManager.query(query);

        // Due to a bug, cursor is null instead of empty when
        // the download manager is disabled.
        if (cursor == null) {
            String msg = getString(R.string.downloadManagerQueryFailed);
            new AlertDialog.Builder(DictionaryManagerActivity.this).setTitle(getString(R.string.error))
            .setMessage(getString(R.string.downloadFailed, msg))
            .setNeutralButton("Close", null).show();
            return;
        }

        while (cursor.moveToNext()) {
            if (downloadUrl.equals(cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_URI))))
                break;
            if (destFile.equals(cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_TITLE))))
                break;
        }
        if (!cursor.isAfterLast()) {
            downloadManager.remove(cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_ID)));
            downloadButton
            .setText(getString(
                         R.string.downloadButton,
                         bytes / 1024.0 / 1024.0));
            cursor.close();
            return;
        }
        cursor.close();
        Request request = new Request(
            Uri.parse(downloadUrl));

        Log.d(LOG, "Downloading to: " + destFile);
        request.setTitle(destFile);

        File destFilePath = new File(application.getDictDir(), destFile);
        destFilePath.delete();
        try {
            request.setDestinationUri(Uri.fromFile(destFilePath));
        } catch (Exception e) {
        }

        try {
            downloadManager.enqueue(request);
        } catch (SecurityException e) {
            request = new Request(Uri.parse(downloadUrl));
            request.setTitle(destFile);
            downloadManager.enqueue(request);
        }
        Log.w(LOG, "Download started: " + destFile);
        downloadButton.setText("X");
    }

}
