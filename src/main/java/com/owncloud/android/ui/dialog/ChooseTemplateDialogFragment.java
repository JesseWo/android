/*
 * Nextcloud Android client application
 *
 * @author Tobias Kaminsky
 * @author Chris Narkiewicz
 *
 * Copyright (C) 2018 Tobias Kaminsky
 * Copyright (C) 2018 Nextcloud GmbH.
 * Copyright (C) 2019 Chris Narkiewicz <hello@ezaquarii.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.dialog;

import android.accounts.Account;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.EditText;

import com.nextcloud.android.lib.resources.directediting.DirectEditingObtainListOfTemplatesRemoteOperation;
import com.nextcloud.client.account.CurrentAccountProvider;
import com.nextcloud.client.di.Injectable;
import com.owncloud.android.MainApp;
import com.owncloud.android.R;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.files.CreateFileFromTemplateOperation;
import com.owncloud.android.lib.common.Creator;
import com.owncloud.android.lib.common.OwnCloudAccount;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory;
import com.owncloud.android.lib.common.Template;
import com.owncloud.android.lib.common.TemplateList;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.ui.activity.ExternalSiteWebView;
import com.owncloud.android.ui.activity.RichDocumentsEditorWebView;
import com.owncloud.android.ui.adapter.TemplateAdapter;
import com.owncloud.android.utils.DisplayUtils;
import com.owncloud.android.utils.ThemeUtils;

import org.parceler.Parcels;

import java.lang.ref.WeakReference;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * Dialog to show templates for new documents/spreadsheets/presentations.
 */
public class ChooseTemplateDialogFragment extends DialogFragment implements DialogInterface.OnClickListener,
    TemplateAdapter.ClickListener, Injectable {

    private static final String ARG_PARENT_FOLDER = "PARENT_FOLDER";
    private static final String ARG_TEMPLATE = "TEMPLATE";
    private static final String ARG_EDITOR = "EDITOR";
    private static final String ARG_MIMETYPE = "MIMETYPE";
    private static final String TAG = ChooseTemplateDialogFragment.class.getSimpleName();
    private static final String DOT = ".";

    private TemplateAdapter adapter;
    private OCFile parentFolder;
    private OwnCloudClient client;
    @Inject CurrentAccountProvider currentAccount;

    public enum Type {
        DOCUMENT,
        SPREADSHEET,
        PRESENTATION
    }

    @BindView(R.id.list)
    RecyclerView listView;

    @BindView(R.id.filename)
    EditText fileName;

    public static ChooseTemplateDialogFragment newInstance(OCFile parentFolder, Creator creator) {
        ChooseTemplateDialogFragment frag = new ChooseTemplateDialogFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_PARENT_FOLDER, parentFolder);
        args.putString(ARG_EDITOR, creator.getEditor());
        args.putString(ARG_TEMPLATE, creator.getId());
        args.putString(ARG_MIMETYPE, creator.getMimetype());
        frag.setArguments(args);
        return frag;

    }

    @Override
    public void onStart() {
        super.onStart();

        int color = ThemeUtils.primaryAccentColor(getContext());

        AlertDialog alertDialog = (AlertDialog) getDialog();

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Bundle arguments = getArguments();
        if (arguments == null) {
            throw new IllegalArgumentException("Arguments may not be null");
        }

        Activity activity = getActivity();
        if (activity == null) {
            throw new IllegalArgumentException("Activity may not be null");
        }

        int accentColor = ThemeUtils.primaryAccentColor(getContext());

        parentFolder = arguments.getParcelable(ARG_PARENT_FOLDER);
        String editor = arguments.getString(ARG_EDITOR);
        String template = arguments.getString(ARG_TEMPLATE);
        String mimetype = arguments.getString(ARG_MIMETYPE);

        // Inflate the layout for the dialog
        LayoutInflater inflater = activity.getLayoutInflater();
        @SuppressLint("InflateParams") View view = inflater.inflate(R.layout.choose_template, null);
        ButterKnife.bind(this, view);

        fileName.requestFocus();
        fileName.getBackground().setColorFilter(accentColor, PorterDuff.Mode.SRC_ATOP);

        try {
            Account account = currentAccount.getCurrentAccount();
            OwnCloudAccount ocAccount = new OwnCloudAccount(account, activity);
            client = OwnCloudClientManagerFactory.getDefaultSingleton().getClientFor(ocAccount, getContext());

            new FetchTemplateTask(this, client, editor, template).execute();
        } catch (Exception e) {
            Log_OC.e(TAG, "Loading stream url not possible: " + e);
        }

        listView.setHasFixedSize(true);
        listView.setLayoutManager(new GridLayoutManager(activity, 2));
        adapter = new TemplateAdapter(mimetype, this, getContext(), currentAccount);
        listView.setAdapter(adapter);

        // Build the dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setView(view)
            .setNegativeButton(R.string.common_cancel, this)
            .setTitle(ThemeUtils.getColoredTitle(getResources().getString(R.string.select_template), accentColor));
        Dialog dialog = builder.create();

        Window window = dialog.getWindow();

        if (window != null) {
            window.setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        return dialog;
    }

    private void createFromTemplate(Template template, String path) {
        new CreateFileFromTemplateTask(this, client, template, path).execute();
    }

    public void setTemplateList(TemplateList templateList) {
        adapter.setTemplateList(templateList);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onClick(Template template) {
        String name = fileName.getText().toString();
        String path = parentFolder.getRemotePath() + name;

        if (name.isEmpty() || name.equalsIgnoreCase(DOT + template.getExtension())) {
            DisplayUtils.showSnackMessage(listView, R.string.enter_filename);
        } else if (!name.endsWith(template.getExtension())) {
            createFromTemplate(template, path + DOT + template.getExtension());
        } else {
            createFromTemplate(template, path);
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        // cancel is handled by dialog itself, no other button available
    }

    private static class CreateFileFromTemplateTask extends AsyncTask<Void, Void, String> {
        private OwnCloudClient client;
        private WeakReference<ChooseTemplateDialogFragment> chooseTemplateDialogFragmentWeakReference;
        private Template template;
        private String path;

        CreateFileFromTemplateTask(ChooseTemplateDialogFragment chooseTemplateDialogFragment, OwnCloudClient client,
                                   Template template, String path) {
            this.client = client;
            this.chooseTemplateDialogFragmentWeakReference = new WeakReference<>(chooseTemplateDialogFragment);
            this.template = template;
            this.path = path;
        }

        @Override
        protected String doInBackground(Void... voids) {
            RemoteOperationResult result = new CreateFileFromTemplateOperation(path, template.getId()).execute(client);

            if (result.isSuccess()) {
                return result.getData().get(0).toString();
            } else {
                return "";
            }
        }

        @Override
        protected void onPostExecute(String url) {
            ChooseTemplateDialogFragment fragment = chooseTemplateDialogFragmentWeakReference.get();

            if (fragment != null) {
                if (url.isEmpty()) {
                    DisplayUtils.showSnackMessage(fragment.listView, "Error creating file from template");
                } else {
                    Intent collaboraWebViewIntent = new Intent(MainApp.getAppContext(), RichDocumentsEditorWebView.class);
                    collaboraWebViewIntent.putExtra(ExternalSiteWebView.EXTRA_TITLE, "Collabora");
                    collaboraWebViewIntent.putExtra(ExternalSiteWebView.EXTRA_URL, url);
                    collaboraWebViewIntent.putExtra(ExternalSiteWebView.EXTRA_SHOW_SIDEBAR, false);
                    collaboraWebViewIntent.putExtra(ExternalSiteWebView.EXTRA_TEMPLATE, Parcels.wrap(template));
                    fragment.startActivity(collaboraWebViewIntent);

                    fragment.dismiss();
                }
            } else {
                Log_OC.e(TAG, "Error creating file from template!");
            }
        }
    }

    private static class FetchTemplateTask extends AsyncTask<Void, Void, TemplateList> {

        private OwnCloudClient client;
        private WeakReference<ChooseTemplateDialogFragment> chooseTemplateDialogFragmentWeakReference;
        private String editor;
        private String template;

        FetchTemplateTask(ChooseTemplateDialogFragment chooseTemplateDialogFragment,
                          OwnCloudClient client,
                          String editor,
                          String template) {
            this.client = client;
            this.chooseTemplateDialogFragmentWeakReference = new WeakReference<>(chooseTemplateDialogFragment);
            this.editor = editor;
            this.template = template;
        }

        @Override
        protected TemplateList doInBackground(Void... voids) {
            RemoteOperationResult result = new DirectEditingObtainListOfTemplatesRemoteOperation(editor, template)
                .execute(client);

            if (!result.isSuccess()) {
                return new TemplateList();
            }

            return (TemplateList) result.getSingleData();
        }

        @Override
        protected void onPostExecute(TemplateList templateList) {
            ChooseTemplateDialogFragment fragment = chooseTemplateDialogFragmentWeakReference.get();

            if (fragment != null) {
                if (templateList.templates.isEmpty()) {
                    DisplayUtils.showSnackMessage(fragment.listView, R.string.error_retrieving_templates);
                } else {
                    fragment.setTemplateList(templateList);

                    String name = DOT + templateList.templates.values().iterator().next().getExtension();
                    fragment.fileName.setText(name);
                }
            } else {
                Log_OC.e(TAG, "Error streaming file: no previewMediaFragment!");
            }
        }
    }
}
