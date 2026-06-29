package com.blueapps.egyptianwriter.editor.document;

import static com.blueapps.egyptianwriter.editor.document.EditorViewModel.MODE_READ;
import static com.blueapps.egyptianwriter.editor.document.EditorViewModel.MODE_WRITE;

import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.blueapps.egyptianwriter.CheckableImageButton;
import com.blueapps.egyptianwriter.R;
import com.blueapps.egyptianwriter.bliss.BlissTranslateFragment;
import com.blueapps.egyptianwriter.bliss.BlissViewModel;
import com.blueapps.egyptianwriter.dashboard.documents.DocumentFragment;
import com.blueapps.egyptianwriter.databinding.ActivityDocumentEditorBinding;
import com.blueapps.egyptianwriter.editor.document.edit.EditFragment;
import com.blueapps.egyptianwriter.editor.document.settings.PropertiesFragment;
import com.blueapps.egyptianwriter.editor.document.settings.PropertiesManager;
import com.blueapps.thoth.ThothListener;
import com.blueapps.thoth.ThothView;
import com.otaliastudios.zoom.ZoomLayout;

import net.cachapa.expandablelayout.ExpandableLayout;

import org.w3c.dom.Document;

public class DocumentEditorActivity extends AppCompatActivity implements ImageButtonListener, ThothListener {

    private ActivityDocumentEditorBinding binding;
    private static final String TAG = "DocumentEditorActivity";

    private EditorViewModel viewModel;
    private BlissViewModel blissViewModel;
    private PropertiesManager propertiesManager;

    private DisplayMetrics displayMetrics;

    private FragmentManager fragmentManager;

    private String filename = "";

    // Views
    private View root;
    private TextView documentTitle;
    private ImageButton buttonBack;
    private ImageButton buttonMode;
    private ThothView thothView;
    private ExpandableLayout expandableLayout;
    private ConstraintLayout background;
    private FragmentContainerView containerView;
    private CheckableImageButton buttonWrite;
    private CheckableImageButton buttonSettings;
    private CheckableImageButton buttonBliss;
    private ZoomLayout zoomLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create non fullscreen layout
        binding = ActivityDocumentEditorBinding.inflate(getLayoutInflater());
        EdgeToEdge.enable(this);
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets imeInsets  = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets navInsets  = insets.getInsets(WindowInsetsCompat.Type.navigationBars());

            int topInset    = Math.max(navInsets.top, systemBars.top);
            int bottomInset = Math.max(imeInsets.bottom, navInsets.bottom);

            root.setPadding(navInsets.left, topInset, navInsets.right, bottomInset);
            return insets;
        });

        // get Extras
        Intent intent = getIntent();
        String name = intent.getStringExtra(DocumentFragment.KEY_NAME);
        filename = intent.getStringExtra(DocumentFragment.KEY_FILE_NAME);

        // get ViewModels
        viewModel        = new ViewModelProvider(this).get(EditorViewModel.class);
        propertiesManager= new ViewModelProvider(this).get(PropertiesManager.class);
        blissViewModel   = new ViewModelProvider(this).get(BlissViewModel.class);

        // Bind views
        root             = binding.getRoot();
        documentTitle    = binding.documentTitle;
        buttonBack       = binding.buttonBack;
        buttonMode       = binding.buttonMode;
        thothView        = binding.glyphXView;
        expandableLayout = binding.editorExpandLayout;
        background       = binding.editorContainer;
        containerView    = binding.editFragmentContainer;
        buttonWrite      = binding.buttonWrite;
        buttonSettings   = binding.buttonSettings;
        buttonBliss      = binding.buttonBliss;   // added in layout
        zoomLayout       = binding.zoom;

        displayMetrics   = getResources().getDisplayMetrics();

        documentTitle.setText(name);
        buttonBack.setOnClickListener(view -> finish());
        buttonMode.setOnClickListener(view -> {
            if (viewModel.isNoIssue()) {
                if (viewModel.getMode()) {
                    viewModel.setMode(MODE_WRITE);
                    buttonMode.setImageDrawable(
                        AppCompatResources.getDrawable(this, R.drawable.edit_note));
                    expandableLayout.expand(true);
                } else {
                    viewModel.setMode(MODE_READ);
                    buttonMode.setImageDrawable(
                        AppCompatResources.getDrawable(this, R.drawable.opened_book));
                    expandableLayout.collapse(true);
                }
            }
        });

        if (viewModel.getMode()) {
            expandableLayout.collapse(false);
        } else {
            expandableLayout.expand(false);
        }

        viewModel.setFileMaster(new FileMaster(this, root, filename));
        viewModel.getFileMaster().addFileListener(new FileListener() {
            @Override
            public void onGlyphXChanged(Document GlyphX) {
                try { thothView.setGlyphXText(GlyphX); } catch (Exception e) { e.printStackTrace(); }
            }
            @Override public void onMdCChanged(String mdc) {}
            @Override public void onSettingsChanged(Document settings) {}
        });
        propertiesManager.extractData(this);

        try {
            thothView.setGlyphXText(viewModel.getFileMaster().getGlyphX());
        } catch (Exception e) { e.printStackTrace(); }
        thothView.setAltText(viewModel.getFileMaster().getMdc());

        // ── Bliss ViewModel observer → ThothView ─────────────────────────────
        // When BlissTranslateFragment posts a GlyphX Document, forward it to
        // ThothView immediately so the rendered output updates in real time.
        blissViewModel.getGlyphXDocument().observe(this, doc -> {
            if (doc != null) {
                try { thothView.setGlyphXText(doc); } catch (Exception e) { e.printStackTrace(); }
            }
        });

        // ── Fragment setup ────────────────────────────────────────────────────
        fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(containerView.getId(), new EditFragment());
        transaction.commit();

        background.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int height = background.getHeight();
            ConstraintLayout.LayoutParams lp =
                (ConstraintLayout.LayoutParams) expandableLayout.getLayoutParams();
            lp.height = (int) (height * 0.5);
            expandableLayout.setLayoutParams(lp);
        });

        // ── Tab buttons: 0=Write 1=Settings 2=Bliss ──────────────────────────
        ImageButtonGroup imageButtonGroup = new ImageButtonGroup();
        imageButtonGroup.addImageButton(buttonWrite);
        imageButtonGroup.addImageButton(buttonSettings);
        imageButtonGroup.addImageButton(buttonBliss);
        imageButtonGroup.addImageButtonListener(this);

        // ThothView properties observers
        propertiesManager.getTextSize().observe(this, integer ->
            thothView.setTextSize((int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, integer, displayMetrics)));
        propertiesManager.getWritingLayout().observe(this,
            integer -> thothView.setWritingLayout(integer));
        propertiesManager.getVerticalOrientation().observe(this,
            integer -> thothView.setVerticalOrientation(integer));
        propertiesManager.getWritingDirection().observe(this,
            integer -> thothView.setWritingDirection(integer));

        // ThothView padding init
        thothView.setSignPadding(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, displayMetrics));
        thothView.setLayoutSignPadding(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, displayMetrics));
        thothView.setInterLinePadding(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15, displayMetrics));
        thothView.setThothListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    // ── ImageButtonGroup callback (tab switch) ────────────────────────────────

    @Override
    public void OnPositionChanges(int position) {
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        switch (position) {
            case 0:
                transaction.replace(containerView.getId(), new EditFragment());
                break;
            case 1:
                transaction.replace(containerView.getId(), new PropertiesFragment());
                break;
            case 2:
                // Bliss translator tab — detect language from device locale,
                // fall back to Italian (primary target language).
                String lang = getResources().getConfiguration()
                    .getLocales().get(0).getLanguage();
                if (!com.blueapps.egyptianwriter.bliss.BlissLookup.SUPPORTED_LANGS
                        .contains(lang)) {
                    lang = "it";
                }
                transaction.replace(
                    containerView.getId(),
                    BlissTranslateFragment.Companion.newInstance(lang));
                break;
        }
        transaction.commit();
    }

    // ── ThothListener ────────────────────────────────────────────────────────

    @Override public void OnRenderStart()                { Log.d(TAG, "Render started!"); }
    @Override public void OnRender(float v, int i, int i1){ Log.d(TAG, "Rendering: " + v + "% [" + i + "/" + i1 + "]"); }
    @Override public void OnRenderCancel()               { Log.d(TAG, "Render canceled!"); }
    @Override public void OnRenderFinished()             { Log.d(TAG, "Render finished!"); }
}
