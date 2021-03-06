package com.coal.projects.chat.presentation.contacts;

import android.arch.lifecycle.ViewModelProviders;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.design.widget.TextInputLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.util.DiffUtil;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import com.coal.projects.chat.creation.ChatInstance;
import com.coal.projects.chat.R;
import com.coal.projects.chat.data.FirebaseRepository;
import com.coal.projects.chat.databinding.ActivityContactsBinding;
import com.coal.projects.chat.domain.utils.ContactsDiffUtilCallback;
import com.coal.projects.chat.presentation.base.BaseActivity;
import com.coal.projects.chat.presentation.base.ModelFactory;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.coal.projects.chat.NotificationHelper.CHAT_INNER_ACTION;

public class ContactsActivity extends BaseActivity {

    public static final String ARRAY_DISPLAY_NAMES = "ARRAY_DISPLAY_NAMES";
    public static final String ARRAY_IDENTIFIER_VALUES = "ARRAY_IDENTIFIER_VALUES";

    private ContactsViewModel viewModel;
    private ActivityContactsBinding binding;
    private ContactsAdapter adapter;

    private FirebaseRepository firebaseRepository;

    private AlertDialog dialogFromBuilder;
    private View dialogView;
    private TextInputLayout layout;
    private EditText editText;
    private Button button;
    private Disposable behaviorDisposable;
    private BehaviorSubject<String> inputBehavior = BehaviorSubject.create();

    private ChatInstance chatInstance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        notifyScreen();
        setTitle("Contacts");
        chatInstance = ChatInstance.getInstance();

        firebaseRepository = chatInstance.getFirebaseRepository();

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_contacts);
        viewModel = ViewModelProviders.of(this, new ModelFactory()).get(ContactsViewModel.class);
        viewModel.setSavedInstanceState(savedInstanceState);
        getLifecycle().addObserver(viewModel);
        viewModel.setSelectionMode(getIntent().getStringExtra("SELECTION_MODE") != null);

        dialogView = getLayoutInflater().inflate(R.layout.add_contact_layout, null);
        layout = dialogView.findViewById(R.id.add_contact_layout);
        editText = dialogView.findViewById(R.id.add_contact_edit_text);
        button = dialogView.findViewById(R.id.add_contact_button);

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                search();
                if (s != null && !s.toString().isEmpty()) {
                    inputBehavior.onNext(s.toString());
                } else
                    isEmptyField();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        button.setOnClickListener(v -> {
            if (editText.getText() != null && !editText.getText().toString().isEmpty()) {
                viewModel.addContact(editText.getText().toString());
                dialogFromBuilder.dismiss();
            }
        });

        dialogFromBuilder = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle(R.string.add_user_title)
                .create();

        binding.fabAddContact.setOnClickListener(v -> showDialog());
        binding.fabWrite.setOnClickListener(view -> {
            if (viewModel.makeResult(getIntent())) {
                setResult(RESULT_OK, getIntent());
                finish();
            } else {
                Toast.makeText(this, "No one contact selected !", Toast.LENGTH_LONG).show();
            }
        });

        viewModel.isProgress.observe(this, progress -> binding.setModel(viewModel));
        viewModel.dismissDialog.observe(this, dismiss -> {
            if (dismiss) closeDialog();
        });

        binding.contactsRecyclerView.setHasFixedSize(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        binding.contactsRecyclerView.setLayoutManager(layoutManager);
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(binding.contactsRecyclerView.getContext(),
                layoutManager.getOrientation());
        binding.contactsRecyclerView.addItemDecoration(dividerItemDecoration);

        adapter = new ContactsAdapter(chatInstance.getImageLoader(), viewModel.isSelectionMode(), this::toggleButton);
        binding.contactsRecyclerView.setAdapter(adapter);

        viewModel.listContacts.observe(this, this::updateContacts);
        viewModel.selection.observe(this, selection -> {
            binding.fabWrite.setEnabled(selection);
            binding.notifyChange();
        });
    }

    private void notifyScreen() {
        Intent intent = getIntent();
        intent.setAction(CHAT_INNER_ACTION);
        sendBroadcast(intent);
    }

    private void toggleButton(List<SelectableUser> items) {
        boolean haveSelection = false;
        ArrayList<SelectableUser> selectableUsers = new ArrayList<>();
        for (SelectableUser user : items) {
            if (user.isSelected()) {
                haveSelection = true;
                selectableUsers.add(user);
            }
        }
        viewModel.setSelectedUsers(selectableUsers);
        viewModel.toggleButton(haveSelection);
    }

    private void updateContacts(List<SelectableUser> contacts) {

        viewModel.writeContacts(contacts);

        ContactsDiffUtilCallback contactsDiffUtilCallback =
                new ContactsDiffUtilCallback(adapter.getItems(), contacts);
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(contactsDiffUtilCallback);

        adapter.setItems(contacts);
        diffResult.dispatchUpdatesTo(adapter);
    }

    private void showDialog() {
        if (!dialogFromBuilder.isShowing()) {
            dialogFromBuilder.show();
        }
    }

    private void closeDialog() {
        if (dialogFromBuilder.isShowing()) {
            dialogFromBuilder.dismiss();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        editText.setText("");
        button.setEnabled(false);
        behaviorDisposable = inputBehavior
                .debounce(1000, TimeUnit.MILLISECONDS)
                .map(text -> firebaseRepository
                        .findUserByEmail(text)
                        .debounce(500, TimeUnit.MILLISECONDS)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::isUserFound, this::onErrorReceived)
                ).subscribe();
    }

    private void onErrorReceived(Throwable throwable) {
        throwable.printStackTrace();
    }

    private void userNotFound() {
        button.setEnabled(false);
        layout.setErrorTextAppearance(R.style.ErrorTextRed);
        layout.setError(getString(R.string.user_not_found));
    }

    private void userFound() {
        button.setEnabled(true);
        layout.setErrorTextAppearance(R.style.ErrorTextGreen);
        layout.setError(getString(R.string.user_found));
    }

    private void isUserFound(Boolean isFound) {
        if (editText.getText() != null && !editText.getText().toString().isEmpty())
            if (isFound)
                userFound();
            else
                userNotFound();
    }

    private void isEmptyField() {
        button.setEnabled(false);
        layout.setErrorEnabled(false);
    }

    private void search() {
        isEmptyField();
        button.setEnabled(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (behaviorDisposable != null && !behaviorDisposable.isDisposed()) {
            behaviorDisposable.dispose();
        }
    }
}
