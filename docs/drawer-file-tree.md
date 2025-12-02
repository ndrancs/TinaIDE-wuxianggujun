# Drawer File Tree 使用说明

本文档展示主界面侧滑栏文件树的完整实现，包括布局、ViewModel、树节点渲染以及弹窗文件管理器代码，便于直接复制到其它项目中复用。所有代码均保持原始结构，以方便对照源码定位。


## 集成概览（KISS / YAGNI）

- `main_fragment.xml` 将 `TreeFileManagerFragment` 直接挂在 `DrawerLayout` 侧边，点击工具栏导航按钮即可控制抽屉。
- `TreeFileManagerFragment` 通过 `FileViewModel` 与 `TreeView` 渲染整棵文件树，点击文件自动交给 `FileEditorManagerImpl`，长按触发 `ActionManager`。
- 树节点样式来自 `file_manager_item.xml` + `TreeFileNodeViewFactory/Binder`，同时被 `FileManagerAdapter` 复用，保证 DRY。
- `TreeUtil` 负责生成 `TreeNode<TreeFile>` 结构，`TreeFile`/`TreeFolder` 等模型只处理图标职责，符合 SRP。
- 如果只想用弹窗式文件管理，`FileManagerFragment` + `FileManagerAdapter` 组合即可独立工作。


## 布局与资源完整代码

### 主界面 Drawer 布局

`app/src/main/res/layout/main_fragment.xml`

```xml
<com.tyron.code.util.AllowChildInterceptDrawerLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:clickable="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <com.google.android.material.appbar.AppBarLayout
            android:id="@+id/app_bar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:elevation="0dp">

            <com.tyron.actions.impl.ActionToolbar
                android:id="@+id/toolbar"
                android:layout_width="match_parent"
                android:layout_height="wrap_content" />
        </com.google.android.material.appbar.AppBarLayout>

        <com.google.android.material.progressindicator.LinearProgressIndicator
            android:id="@+id/progressbar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

        <!-- This extra parent is needed so window insets will still be dispatched to other views-->
        <com.tyron.code.util.NoInsetFrameLayout
            android:layout_width="match_parent"
            android:fillViewport="true"
            android:fitsSystemWindows="true"
            android:layout_height="match_parent">

            <androidx.fragment.app.FragmentContainerView
                android:id="@+id/root"
                android:name="com.tyron.code.ui.editor.EditorContainerFragment"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                tools:layout="@layout/editor_container_fragment">

            </androidx.fragment.app.FragmentContainerView>
        </com.tyron.code.util.NoInsetFrameLayout>
    </LinearLayout>

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/nav_root"
        android:name="com.tyron.code.ui.file.tree.TreeFileManagerFragment"
        android:layout_width="300dp"
        android:layout_height="match_parent"
        android:background="?android:attr/colorBackground"
        android:layout_gravity="start" />

</com.tyron.code.util.AllowChildInterceptDrawerLayout>
```

### 树 Fragment 根布局

`app/src/main/res/layout/tree_file_manager_fragment.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.swiperefreshlayout.widget.SwipeRefreshLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/refreshLayout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true"
    xmlns:tools="http://schemas.android.com/tools">

    <androidx.core.widget.NestedScrollView
        android:layout_width="match_parent"
        android:fillViewport="true"
        android:layout_height="match_parent">

        <HorizontalScrollView
            android:id="@+id/horizontalScrollView"
            android:fillViewport="true"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

        </HorizontalScrollView>
    </androidx.core.widget.NestedScrollView>

</androidx.swiperefreshlayout.widget.SwipeRefreshLayout>
```

### 文件/节点行布局

`app/src/main/res/layout/file_manager_item.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
	xmlns:android="http://schemas.android.com/apk/res/android"
	xmlns:app="http://schemas.android.com/apk/res-auto"
	android:layout_width="match_parent"
	android:layout_height="wrap_content"
	android:orientation="horizontal"
	android:gravity="start|center_vertical"
	android:paddingTop="4dp"
	android:paddingBottom="4dp"
	android:paddingStart="8dp"
	android:paddingEnd="8dp">

	<ImageView
		android:id="@+id/arrow"
		android:layout_width="24dp"
		android:layout_height="24dp"
		android:layout_marginEnd="8dp"
		android:src="@drawable/ic_baseline_keyboard_arrow_right_24" />

	<ImageView
		android:id="@+id/icon"
		android:layout_width="24dp"
		android:layout_height="24dp"
		android:src="@drawable/round_folder_24" />

	<TextView
        android:id="@+id/name"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content"
		android:text="Medium Text"
		android:layout_marginStart="8dp"
		android:layout_marginEnd="8dp"/>

</LinearLayout>
```

### 文件管理器弹窗布局

`app/src/main/res/layout/file_manager_fragment.xml`

```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="fill_parent"
    android:layout_height="fill_parent"
    android:orientation="vertical">
    
   <androidx.recyclerview.widget.RecyclerView
       android:id="@+id/listView"
       android:layout_width="match_parent"
       android:layout_height="match_parent" />
</LinearLayout>
```

## Java / Kotlin 源码完整代码

### MainFragment

`app/src/main/java/com/tyron/code/ui/main/MainFragment.java`

```java
package com.tyron.code.ui.main;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.transition.MaterialSharedAxis;
import com.google.common.base.Throwables;
import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.util.DataContextUtils;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.gradle.util.GradleLaunchUtil;
import com.tyron.code.ui.editor.log.AppLogFragment;
import com.tyron.code.ui.file.FileViewModel;
import com.tyron.code.ui.file.event.RefreshRootEvent;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.UiUtilsKt;
import com.tyron.common.logging.IdeLog;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.fileeditor.api.FileEditor;

import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProgressListener;
import org.gradle.tooling.ProjectConnection;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import javax.tools.Diagnostic;

public class MainFragment extends Fragment implements ProjectManager.OnProjectOpenListener {

    public static final String REFRESH_TOOLBAR_KEY = "refreshToolbar";

    public static final Key<CompileCallback> COMPILE_CALLBACK_KEY = Key.create("compileCallback");
    public static final Key<IndexCallback> INDEX_CALLBACK_KEY = Key.create("indexCallbackKey");
    public static final Key<MainViewModel> MAIN_VIEW_MODEL_KEY = Key.create("mainViewModel");

    private Handler mHandler;

    public static MainFragment newInstance(@NonNull String projectPath) {
        Bundle bundle = new Bundle();
        bundle.putString("project_path", projectPath);

        MainFragment fragment = new MainFragment();
        fragment.setArguments(bundle);

        return fragment;
    }

    private LogViewModel mLogViewModel;
    private MainViewModel mMainViewModel;
    private FileViewModel mFileViewModel;

    private ProjectManager mProjectManager;
    private View mRoot;
    private Toolbar mToolbar;
    private LinearProgressIndicator mProgressBar;
    private BroadcastReceiver mLogReceiver;

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (mRoot instanceof DrawerLayout) {
                //noinspection ConstantConditions
                if (mMainViewModel.getDrawerState().getValue()) {
                    mMainViewModel.setDrawerState(false);
                }
            }
        }
    };
    private Project mProject;

    private final CompileCallback mCompileCallback = this::compile;
    private final IndexCallback mIndexCallback = this::openProject;


    public MainFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));

        requireActivity().getOnBackPressedDispatcher()
                .addCallback((LifecycleOwner) this, onBackPressedCallback);

        String projectPath = requireArguments().getString("project_path");
        mProject = new Project(new File(projectPath));
        mProjectManager = ProjectManager.getInstance();
        mProjectManager.addOnProjectOpenListener(this);
        mLogViewModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        mFileViewModel = new ViewModelProvider(requireActivity()).get(FileViewModel.class);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        mRoot = inflater.inflate(R.layout.main_fragment, container, false);

        mProgressBar = mRoot.findViewById(R.id.progressbar);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.GONE);

        mToolbar = mRoot.findViewById(R.id.toolbar);
        mToolbar.setNavigationIcon(R.drawable.ic_baseline_menu_24);
        UiUtilsKt.addSystemWindowInsetToPadding(mToolbar, false, true, false, false);

        getChildFragmentManager().setFragmentResultListener(REFRESH_TOOLBAR_KEY,
                getViewLifecycleOwner(),
                (key, __) -> refreshToolbar());

        refreshToolbar();

        if (savedInstanceState != null) {
            restoreViewState(savedInstanceState);
        }
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (mRoot instanceof DrawerLayout) {
            DrawerLayout drawerLayout = (DrawerLayout) mRoot;
            mToolbar.setNavigationOnClickListener(v -> {
                if (mRoot instanceof DrawerLayout) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        mMainViewModel.setDrawerState(false);
                    } else if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        mMainViewModel.setDrawerState(true);
                    }
                }
            });
            drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
                @Override
                public void onDrawerOpened(@NonNull View p1) {
                    mMainViewModel.setDrawerState(true);
                    onBackPressedCallback.setEnabled(true);
                }

                @Override
                public void onDrawerClosed(@NonNull View p1) {
                    mMainViewModel.setDrawerState(false);
                    onBackPressedCallback.setEnabled(false);
                }
            });
        } else {
            mToolbar.setNavigationIcon(null);
        }

        File root;
        if (mProject != null) {
            root = mProject.getRootFile();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                root = requireActivity().getExternalFilesDir(null);
            } else {
                root = Environment.getExternalStorageDirectory();
            }
        }
        mFileViewModel.refreshNode(root);

        if (!mProject.equals(mProjectManager.getCurrentProject())) {
            mRoot.postDelayed(() -> openProject(mProject), 200);
        }

        // If the user has changed projects, clear the current opened files
        if (!mProject.equals(mProjectManager.getCurrentProject())) {
            mMainViewModel.setFiles(new ArrayList<>());
            mLogViewModel.clear(LogViewModel.BUILD_LOG);
        }
        mMainViewModel.isIndexing().observe(getViewLifecycleOwner(), indexing -> {
            mProgressBar.setVisibility(indexing ? View.VISIBLE : View.GONE);
            CompletionEngine.setIndexing(indexing);
            refreshToolbar();
        });
        mMainViewModel.getCurrentState().observe(getViewLifecycleOwner(), mToolbar::setSubtitle);
        mMainViewModel.getToolbarTitle().observe(getViewLifecycleOwner(), mToolbar::setTitle);
        if (mRoot instanceof DrawerLayout) {
            mMainViewModel.getDrawerState().observe(getViewLifecycleOwner(), isOpen -> {
                if (isOpen) {
                    ((DrawerLayout) mRoot).open();
                } else {
                    ((DrawerLayout) mRoot).close();
                }
            });
        }

        mHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Level level = record.getLevel();
                if (Level.WARNING.equals(level)) {
                    mLogViewModel.w(LogViewModel.IDE, record.getMessage());
                } else if (Level.SEVERE.equals(level)) {
                    mLogViewModel.e(LogViewModel.IDE, record.getMessage());
                } else {
                    mLogViewModel.d(LogViewModel.IDE, record.getMessage());
                }
            }

            @Override
            public void flush() {
                mLogViewModel.clear(LogViewModel.IDE);
            }

            @Override
            public void close() throws SecurityException {
                mLogViewModel.clear(LogViewModel.IDE);
            }
        };
        IdeLog.getLogger().addHandler(mHandler);

        // can be null on tablets
        View navRoot = view.findViewById(R.id.nav_root);

        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, insets) -> {
            if (navRoot != null) {
                ViewCompat.dispatchApplyWindowInsets(navRoot, insets);
            }
            ViewGroup viewGroup = (ViewGroup) mRoot;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                if (child == navRoot) {
                    continue;
                }

                ViewCompat.dispatchApplyWindowInsets(child, insets);
            }
            return ViewCompat.onApplyWindowInsets(v, insets);
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ProjectManager manager = ProjectManager.getInstance();
        manager.removeOnProjectOpenListener(this);

        if (mLogReceiver != null) {
            requireActivity().unregisterReceiver(mLogReceiver);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (mHandler != null) {
            IdeLog.getLogger().removeHandler(mHandler);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        refreshToolbar();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        if (mRoot instanceof DrawerLayout) {
            outState.putBoolean("start_drawer_state",
                    ((DrawerLayout) mRoot).isDrawerOpen(GravityCompat.START));
        }
        super.onSaveInstanceState(outState);
    }

    private void restoreViewState(@NonNull Bundle state) {
        if (mRoot instanceof DrawerLayout) {
            boolean b = state.getBoolean("start_drawer_state", false);
            mMainViewModel.setDrawerState(b);
        }
    }

    /**
     * Tries to open a file into the editor
     *
     * @param file file to open
     */
    public void openFile(FileEditor file) {
        mMainViewModel.openFile(file);
    }

    public void openProject(@NonNull Project project) {
        if (CompletionEngine.isIndexing()) {
            return;
        }
        if (getContext() == null) {
            return;
        }

        if (project.equals(ProjectManager.getInstance().getCurrentProject())) {
            project.getSettings().refresh();
        }

//        IndexServiceConnection.restoreFileEditors(project, mMainViewModel);

        mProject = project;

        mMainViewModel.setToolbarTitle(project.getRootFile().getName());
        mMainViewModel.setIndexing(true);
        CompletionEngine.setIndexing(true);

        RefreshRootEvent event = new RefreshRootEvent(project.getRootFile());
        ApplicationLoader.getInstance().getEventManager().dispatchEvent(event);

        ProgressManager.getInstance()
                .runNonCancelableAsync(() -> ProjectManager.getInstance()
                        .openProject(project,
                                false,
                                new TaskListener(),
                                ILogger.wrap(mLogViewModel)));
    }

    private class TaskListener implements ProjectManager.TaskListener {

        @Override
        public void onTaskStarted(String message) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> mMainViewModel.setCurrentState(message));
            }
        }

        @SuppressWarnings("ConstantConditions")
        @Override
        public void onComplete(Project project, boolean success, String message) {
            if (getActivity() == null) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!success) {
                    AndroidUtilities.showSimpleAlert(requireActivity(),
                            "Index failed.",
                            "Error message: " + message + "\n" +
                            "Code completions may not work properly.",

                            "Close",
                            null,
                            "Copy stacktrace", (dialog, which) -> {
                                if (which == DialogInterface.BUTTON_NEGATIVE) {
                                    AndroidUtilities.copyToClipboard(message);
                                }
                            });
                }
                mMainViewModel.setIndexing(false);
                mMainViewModel.setCurrentState(null);
                if (success) {
                    Project currentProject = ProjectManager.getInstance().getCurrentProject();
                    if (project.equals(currentProject)) {
                        mMainViewModel.setToolbarTitle(project.getRootFile().getName());
                    }
                } else {
                    if (mMainViewModel.getBottomSheetState().getValue() !=
                        BottomSheetBehavior.STATE_EXPANDED) {
                        mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                    }
                    mLogViewModel.e(LogViewModel.BUILD_LOG, message);
                }
            });
        }
    }

    private void compile(BuildType type) {
        if (Boolean.TRUE.equals(mMainViewModel.isIndexing().getValue()) ||
            CompletionEngine.isIndexing()) {
            return;
        }

        mMainViewModel.setCurrentState(getString(R.string.compilation_state_compiling));
        mMainViewModel.setIndexing(true);
        mLogViewModel.clear(LogViewModel.BUILD_LOG);

        Runnable compileRunnable = () -> {
            String task;
            switch (type) {
                case RELEASE: task = "installRelease";
                break;
                default:
                case DEBUG: task = "installDebug";
            }
            try {
                GradleConnector gradleConnector = GradleConnector.newConnector()
                        .useDistribution(URI.create("codeAssist"))
                        .forProjectDirectory(mProject.getRootFile());

                try (ProjectConnection projectConnection = gradleConnector.connect()) {
                    BuildLauncher buildLauncher = projectConnection.newBuild()
                            .setStandardError(AppLogFragment.outputStream)
                            .setStandardOutput(AppLogFragment.outputStream);
                    buildLauncher.addProgressListener((ProgressListener) desc -> {
                        if (getActivity() != null) {
                            requireActivity().runOnUiThread(() -> mMainViewModel.setCurrentState(
                                    desc.getDescription()));
                        }
                    });
                    GradleLaunchUtil.configureLauncher(buildLauncher);
                    GradleLaunchUtil.addCodeAssistInitScript(buildLauncher);

                    buildLauncher.addArguments("--build-cache");
                    buildLauncher.forTasks(task);
                    buildLauncher.run();
                }
            } catch (Throwable t) {
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        mLogViewModel.e(LogViewModel.IDE, Throwables.getStackTraceAsString(t));
                        mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                    });
                }
            } finally {
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        mMainViewModel.setCurrentState(null);
                        mMainViewModel.setIndexing(false);
                    });
                }
            }
        };
        ProgressManager.getInstance().runNonCancelableAsync(compileRunnable);
    }

    @Override
    public void onProjectOpen(Project project) {
        Module module = project.getMainModule();
        if (module instanceof AndroidModule) {
            mLogReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String type = intent.getExtras().getString("type", "DEBUG");
                    String message = intent.getExtras().getString("message", "No message provided");
                    DiagnosticWrapper wrapped = ILogger.wrap(message);

                    switch (type) {
                        case "DEBUG":
                        case "INFO":
                            wrapped.setKind(Diagnostic.Kind.NOTE);
                            mLogViewModel.d(LogViewModel.APP_LOG, wrapped);
                            break;
                        case "ERROR":
                            wrapped.setKind(Diagnostic.Kind.ERROR);
                            mLogViewModel.e(LogViewModel.APP_LOG, wrapped);
                            break;
                        case "WARNING":
                            wrapped.setKind(Diagnostic.Kind.WARNING);
                            mLogViewModel.w(LogViewModel.APP_LOG, wrapped);
                            break;
                    }
                }
            };
            String packageName = ((AndroidModule) module).getPackageName();
            if (packageName != null) {
                requireActivity().registerReceiver(mLogReceiver,
                        new IntentFilter(packageName + ".LOG"));
            } else {
                mLogReceiver = null;
            }
        }

        ProgressManager.getInstance().runLater(() -> {
            if (getContext() == null) {
                return;
            }
            refreshToolbar();
        });
    }

    private void injectData(DataContext context) {
        Boolean indexing = mMainViewModel.isIndexing().getValue();
        // to please lint
        if (indexing == null) {
            indexing = true;
        }
        if (!indexing) {
            context.putData(CommonDataKeys.PROJECT,
                    ProjectManager.getInstance().getCurrentProject());
        }
        context.putData(CommonDataKeys.ACTIVITY, getActivity());
        context.putData(MAIN_VIEW_MODEL_KEY, mMainViewModel);
        context.putData(COMPILE_CALLBACK_KEY, mCompileCallback);
        context.putData(INDEX_CALLBACK_KEY, mIndexCallback);
        context.putData(CommonDataKeys.FILE_EDITOR_KEY, mMainViewModel.getCurrentFileEditor());
    }

    public void refreshToolbar() {
        mToolbar.getMenu().clear();

        DataContext context = DataContextUtils.getDataContext(mToolbar);
        injectData(context);

        Instant now = Instant.now();
        ActionManager.getInstance()
                .fillMenu(context, mToolbar.getMenu(), ActionPlaces.MAIN_TOOLBAR, false, true);
        Log.d("ActionManager",
                "fillMenu() took " + Duration.between(now, Instant.now()).toMillis());
    }
}
```

### TreeFileManagerFragment

`app/src/main/java/com/tyron/code/ui/file/tree/TreeFileManagerFragment.java`

```java
package com.tyron.code.ui.file.tree;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.ThemeUtils;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.BuildConfig;
import com.tyron.code.R;
import com.tyron.code.event.EventManager;
import com.tyron.code.event.EventReceiver;
import com.tyron.code.event.SubscriptionReceipt;
import com.tyron.code.event.Unsubscribe;
import com.tyron.code.ui.file.event.RefreshRootEvent;
import com.tyron.code.util.ApkInstaller;
import com.tyron.code.util.EventManagerUtilsKt;
import com.tyron.code.util.UiUtilsKt;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.file.CommonFileKeys;
import com.tyron.code.ui.file.FileViewModel;
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewBinder.TreeFileNodeListener;
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewFactory;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.ui.project.ProjectManager;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.Executors;

public class TreeFileManagerFragment extends Fragment {

    /**
     * @deprecated Instantiate this fragment directly without arguments and
     * use {@link FileViewModel} to update the nodes
     */
    @Deprecated
    public static TreeFileManagerFragment newInstance(File root) {
        TreeFileManagerFragment fragment = new TreeFileManagerFragment();
        Bundle args = new Bundle();
        args.putSerializable("rootFile", root);
        fragment.setArguments(args);
        return fragment;
    }

    private MainViewModel mMainViewModel;
    private FileViewModel mFileViewModel;
    private TreeView<TreeFile> treeView;

    public TreeFileManagerFragment() {
        super(R.layout.tree_file_manager_fragment);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        mFileViewModel = new ViewModelProvider(requireActivity()).get(FileViewModel.class);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ViewCompat.requestApplyInsets(view);
        UiUtilsKt.addSystemWindowInsetToPadding(view, false, true, false, true);

        SwipeRefreshLayout refreshLayout = view.findViewById(R.id.refreshLayout);
        refreshLayout.setOnRefreshListener(() -> partialRefresh(() -> {
            refreshLayout.setRefreshing(false);
            treeView.refreshTreeView();
        }));


        treeView = new TreeView<>(
                requireContext(), TreeNode.root(Collections.emptyList()));

        HorizontalScrollView horizontalScrollView = view.findViewById(R.id.horizontalScrollView);
        horizontalScrollView.addView(treeView.getView(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        treeView.getView().setNestedScrollingEnabled(false);

        EventManager eventManager = ApplicationLoader.getInstance()
                .getEventManager();

        EventManagerUtilsKt.subscribeEvent(eventManager, getViewLifecycleOwner(), RefreshRootEvent.class, (event, unsubscribe) -> {
            File refreshRoot = event.getRoot();
            TreeNode<TreeFile> currentRoot = treeView.getRoot();
            if (currentRoot != null && refreshRoot.equals(currentRoot.getValue().getFile())) {
                partialRefresh(() -> treeView.refreshTreeView());
            } else {
                ProgressManager.getInstance().runNonCancelableAsync(() -> {
                    TreeNode<TreeFile> node = TreeNode.root(TreeUtil.getNodes(refreshRoot));
                    ProgressManager.getInstance().runLater(() -> {
                        if (getActivity() == null) {
                            return;
                        }
                        treeView.refreshTreeView(node);
                    });
                });
            }
        });

        treeView.setAdapter(new TreeFileNodeViewFactory(new TreeFileNodeListener() {
            @Override
            public void onNodeToggled(TreeNode<TreeFile> treeNode, boolean expanded) {
                if (treeNode.isLeaf()) {
                    File file = treeNode.getValue().getFile();
                    if (file.isFile()) {
                        // TODO: cleaner api to do this
                        if (file.getName().endsWith(".apk")) {
                            ApkInstaller.installApplication(requireContext(), BuildConfig.APPLICATION_ID, file.getAbsolutePath());
                        } else {
                            FileEditorManagerImpl.getInstance().openFile(requireContext(), treeNode.getValue().getFile(), true);
                        }
                    }
                }
            }

            @Override
            public boolean onNodeLongClicked(View view, TreeNode<TreeFile> treeNode, boolean expanded) {
                PopupMenu popupMenu = new PopupMenu(requireContext(), view);
                addMenus(popupMenu, treeNode);
                popupMenu.show();
                return true;
            }
        }));
        mFileViewModel.getNodes().observe(getViewLifecycleOwner(), node -> {
            treeView.refreshTreeView(node);
        });
    }


    private void partialRefresh(Runnable callback) {
        ProgressManager.getInstance().runNonCancelableAsync(() -> {
            if (!treeView.getAllNodes().isEmpty()) {
                TreeNode<TreeFile> node = treeView.getAllNodes().get(0);
                TreeUtil.updateNode(node);
                ProgressManager.getInstance().runLater(() -> {
                    if (getActivity() == null) {
                        return;
                    }
                    callback.run();
                });
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    /**
     * Add menus to the current PopupMenu based on the current {@link TreeNode}
     *
     * @param popupMenu The PopupMenu to add to
     * @param node      The current TreeNode in the file tree
     */
    private void addMenus(PopupMenu popupMenu, TreeNode<TreeFile> node) {
        DataContext dataContext = DataContext.wrap(requireContext());
        dataContext.putData(CommonDataKeys.FILE, node.getContent().getFile());
        dataContext.putData(CommonDataKeys.PROJECT, ProjectManager.getInstance().getCurrentProject());
        dataContext.putData(CommonDataKeys.FRAGMENT, TreeFileManagerFragment.this);
        dataContext.putData(CommonDataKeys.ACTIVITY, requireActivity());
        dataContext.putData(CommonFileKeys.TREE_NODE, node);

        ActionManager.getInstance().fillMenu(dataContext,
                popupMenu.getMenu(), ActionPlaces.FILE_MANAGER,
                true,
                false);
    }

    public TreeView<TreeFile> getTreeView() {
        return treeView;
    }

    public MainViewModel getMainViewModel() {
        return mMainViewModel;
    }

    public FileViewModel getFileViewModel() {
        return mFileViewModel;
    }
}
```

### FileViewModel

`app/src/main/java/com/tyron/code/ui/file/FileViewModel.java`

```java
package com.tyron.code.ui.file;

import android.os.Environment;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.tyron.completion.progress.ProgressManager;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.code.ui.file.tree.TreeUtil;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.io.File;
import java.util.concurrent.Executors;

public class FileViewModel extends ViewModel {

    private MutableLiveData<File> mRoot =
            new MutableLiveData<>(Environment.getExternalStorageDirectory());
    private MutableLiveData<TreeNode<TreeFile>> mNode = new MutableLiveData<>();

    public LiveData<TreeNode<TreeFile>> getNodes() {
        return mNode;
    }

    public LiveData<File> getRootFile() {
        return mRoot;
    }

    public void setRootFile(File root) {
        mRoot.setValue(root);
        refreshNode(root);
    }

    public void setRootNode(TreeNode<TreeFile> rootNode) {
        mNode.setValue(rootNode);
    }

    public void refreshNode(File root) {
        ProgressManager.getInstance().runNonCancelableAsync(() -> {
            TreeNode<TreeFile> node = TreeNode.root(TreeUtil.getNodes(root));
            mNode.postValue(node);
        });
    }
}
```

### TreeUtil

`app/src/main/java/com/tyron/code/ui/file/tree/TreeUtil.java`

```java
package com.tyron.code.ui.file.tree;

import com.tyron.ui.treeview.TreeNode;
import com.tyron.code.ui.file.tree.model.TreeFile;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TreeUtil {

    public static final Comparator<File> FILE_FIRST_ORDER = (file1, file2) -> {
        if (file1.isFile() && file2.isDirectory()) {
            return 1;
        } else if (file2.isFile() && file1.isDirectory()) {
            return -1;
        } else {
            return String.CASE_INSENSITIVE_ORDER.compare(file1.getName(), file2.getName());
        }
    };

    public static TreeNode<TreeFile> getRootNode(TreeNode<TreeFile> node) {
        TreeNode<TreeFile> parent = node.getParent();
        TreeNode<TreeFile> root = node;
        while (parent != null) {
            root = parent;
            parent = parent.getParent();
        }
        return root;
    }

    public static void updateNode(TreeNode<TreeFile> node) {
        Set<File> expandedNodes = TreeUtil.getExpandedNodes(node);
        List<TreeNode<TreeFile>> newChildren = getNodes(node.getValue().getFile(), node.getLevel())
                .get(0).getChildren();
        setExpandedNodes(newChildren, expandedNodes);
        node.setChildren(newChildren);
    }

    private static void setExpandedNodes(List<TreeNode<TreeFile>> nodeList, Set<File> expandedNodes) {
        for (TreeNode<TreeFile> treeFileTreeNode : nodeList) {
            if (expandedNodes.contains(treeFileTreeNode.getValue().getFile())) {
                treeFileTreeNode.setExpanded(true);
            }

            setExpandedNodes(treeFileTreeNode.getChildren(), expandedNodes);
        }
    }

    private static Set<File> getExpandedNodes(TreeNode<TreeFile> node) {
        Set<File> expandedNodes = new HashSet<>();
        if (node.isExpanded()) {
            expandedNodes.add(node.getValue().getFile());
        }
        for (TreeNode<TreeFile> child : node.getChildren()) {
            if (child.getValue().getFile().isDirectory()) {
                expandedNodes.addAll(getExpandedNodes(child));
            }
        }
        return expandedNodes;
    }

    public static List<TreeNode<TreeFile>> getNodes(File rootFile) {
        return getNodes(rootFile, 0);
    }

    /**
     * Get all the tree note at the given root
     */
    public static List<TreeNode<TreeFile>> getNodes(File rootFile, int initialLevel) {
        List<TreeNode<TreeFile>> nodes = new ArrayList<>();
        if (rootFile == null) {
            return nodes;
        }

        TreeNode<TreeFile> root = new TreeNode<>(
                TreeFile.fromFile(rootFile), initialLevel
        );
        root.setExpanded(true);

        File[] children = rootFile.listFiles();
        if (children != null) {
            Arrays.sort(children, FILE_FIRST_ORDER);
            for (File file : children) {
                addNode(root, file, initialLevel + 1);
            }
        }
        nodes.add(root);
        return nodes;
    }

    private static void addNode(TreeNode<TreeFile> node, File file, int level) {
        TreeNode<TreeFile> childNode = new TreeNode<>(
                TreeFile.fromFile(file), level
        );

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                Arrays.sort(children, FILE_FIRST_ORDER);
                for (File child : children) {
                    addNode(childNode, child, level + 1);
                }
            }
        }

        node.addChild(childNode);
    }
}
```

### TreeFile

`app/src/main/java/com/tyron/code/ui/file/tree/model/TreeFile.java`

```java
package com.tyron.code.ui.file.tree.model;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.tyron.code.R;

import java.io.File;
import java.util.Objects;

public class TreeFile {

    @Nullable
    public static TreeFile fromFile(File file) {
        if (file == null) {
            return null;
        }
        if (file.isDirectory()) {
            return new TreeFolder(file);
        }
        if (file.getName().endsWith(".java")) {
            return new TreeJavaFile(file);
        }
        return new TreeFile(file);
    }

    private final File mFile;

    public TreeFile(File file) {
        mFile = file;
    }

    public File getFile() {
        return mFile;
    }

    public Drawable getIcon(Context context) {
        return AppCompatResources.getDrawable(context,
                R.drawable.round_insert_drive_file_24);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TreeFile treeFile = (TreeFile) o;
        return Objects.equals(mFile, treeFile.mFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFile);
    }
}
```

### TreeFolder

`app/src/main/java/com/tyron/code/ui/file/tree/model/TreeFolder.java`

```java
package com.tyron.code.ui.file.tree.model;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.appcompat.content.res.AppCompatResources;

import com.tyron.code.R;

import java.io.File;

public class TreeFolder extends TreeFile {

    public TreeFolder(File file) {
        super(file);
    }

    @Override
    public Drawable getIcon(Context context) {
        return AppCompatResources.getDrawable(context,
                R.drawable.round_folder_20);
    }
}
```

### TreeJavaFile

`app/src/main/java/com/tyron/code/ui/file/tree/model/TreeJavaFile.java`

```java
package com.tyron.code.ui.file.tree.model;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.appcompat.content.res.AppCompatResources;

import com.tyron.code.R;

import java.io.File;

public class TreeJavaFile extends TreeFile {

    public TreeJavaFile(File file) {
        super(file);
    }

    @Override
    public Drawable getIcon(Context context) {
        return super.getIcon(context);
    }
}
```

### TreeFileNodeViewFactory

`app/src/main/java/com/tyron/code/ui/file/tree/binder/TreeFileNodeViewFactory.kt`

```kotlin
package com.tyron.code.ui.file.tree.binder

import android.view.View
import com.tyron.code.R
import com.tyron.ui.treeview.base.BaseNodeViewFactory
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewBinder.TreeFileNodeListener
import com.tyron.code.ui.file.tree.model.TreeFile

class TreeFileNodeViewFactory(
    private var nodeListener: TreeFileNodeListener
): BaseNodeViewFactory<TreeFile>() {

    override fun getNodeViewBinder(view: View, level: Int) =
        TreeFileNodeViewBinder(view, level, nodeListener)

    override fun getNodeLayoutId(level: Int) = R.layout.file_manager_item

}
```

### TreeFileNodeViewBinder

`app/src/main/java/com/tyron/code/ui/file/tree/binder/TreeFileNodeViewBinder.kt`

```kotlin
package com.tyron.code.ui.file.tree.binder

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.tyron.code.R
import com.tyron.ui.treeview.TreeNode
import com.tyron.ui.treeview.base.BaseNodeViewBinder
import com.tyron.code.ui.file.tree.model.TreeFile
import com.tyron.code.util.dp
import com.tyron.code.util.setMargins

class TreeFileNodeViewBinder(
    itemView: View,
    private val level: Int,
    private val nodeListener: TreeFileNodeListener
): BaseNodeViewBinder<TreeFile>(itemView) {

    private lateinit var viewHolder: ViewHolder

    override fun bindView(treeNode: TreeNode<TreeFile>) {
        viewHolder = ViewHolder(itemView)

        viewHolder.rootView.setMargins(
            left = level * 15.dp
        )

        with(viewHolder.arrow) {
            setImageResource(R.drawable.ic_baseline_keyboard_arrow_right_24)
            rotation = if (treeNode.isExpanded) 90F else 0F
            visibility = if (treeNode.isLeaf) View.INVISIBLE else View.VISIBLE
        }

        val file = treeNode.content.file

        viewHolder.dirName.text = file.name

        with(viewHolder.icon) {
            setImageDrawable(treeNode.content.getIcon(context))
        }
    }

    override fun onNodeToggled(treeNode: TreeNode<TreeFile>, expand: Boolean) {
        viewHolder.arrow.animate()
            .rotation(if (expand) 90F else 0F)
            .setDuration(150)
            .start()

        nodeListener.onNodeToggled(treeNode, expand)
    }

    override fun onNodeLongClicked(view: View, treeNode: TreeNode<TreeFile>, expanded: Boolean): Boolean {
        return nodeListener.onNodeLongClicked(view, treeNode, expanded)
    }

    class ViewHolder(val rootView: View) {
        val arrow: ImageView = rootView.findViewById(R.id.arrow)
        val icon: ImageView = rootView.findViewById(R.id.icon)
        val dirName: TextView = rootView.findViewById(R.id.name)
    }

    interface TreeFileNodeListener {
        fun onNodeToggled(treeNode: TreeNode<TreeFile>?, expanded: Boolean)
        fun onNodeLongClicked(view: View?, treeNode: TreeNode<TreeFile>?, expanded: Boolean): Boolean
    }

}
```

### FileManagerFragment

`app/src/main/java/com/tyron/code/ui/file/dialog/FileManagerFragment.java`

```java
package com.tyron.code.ui.file.dialog;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import java.io.File;
import java.util.Objects;

import android.os.Bundle;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.activity.OnBackPressedCallback;

import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.file.FileManagerAdapter;
import com.tyron.code.ui.main.MainFragment;

@SuppressWarnings("FieldCanBeLocal")
public class FileManagerFragment extends Fragment {
    
    public static FileManagerFragment newInstance(File file) {
        FileManagerFragment fragment = new FileManagerFragment();
        Bundle args = new Bundle();
        args.putString("path", file.getAbsolutePath());
        fragment.setArguments(args);
        return fragment;
    }
    
    OnBackPressedCallback callback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (!mCurrentFile.equals(mRootFile)) {
                mAdapter.submitFile(Objects.requireNonNull(mCurrentFile.getParentFile()));
                check(mCurrentFile.getParentFile());
            }
        }
    };

    private File mRootFile;
    private File mCurrentFile;
    
    private LinearLayout mRoot;
    private RecyclerView mListView;
    private LinearLayoutManager mLayoutManager;
    private FileManagerAdapter mAdapter;
    
    public FileManagerFragment() {
        
    }

    public void disableBackListener() {
        callback.setEnabled(false);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assert getArguments() != null;
        mRootFile = new File(getArguments().getString("path"));
        if (savedInstanceState != null) {
            mCurrentFile = new File(savedInstanceState.getString("currentFile"), mRootFile.getAbsolutePath());
        } else {
            mCurrentFile = mRootFile;
        }
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = (LinearLayout) inflater.inflate(R.layout.file_manager_fragment, container, false);

        mLayoutManager = new LinearLayoutManager(requireContext());
        mAdapter = new FileManagerAdapter();
        
        mListView = mRoot.findViewById(R.id.listView);
        mListView.setLayoutManager(mLayoutManager);
        mListView.setAdapter(mAdapter);
        
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), callback);
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        mAdapter.submitFile(mCurrentFile);
        mAdapter.setOnItemClickListener((file, position) -> {
            if (position == 0) {
                if (!mCurrentFile.equals(mRootFile)) {
                    mAdapter.submitFile(Objects.requireNonNull(mCurrentFile.getParentFile()));
                    check(mCurrentFile.getParentFile());
                }
                return;
            }

            if (file.isFile()) {
                openFile(file);
            } else if (file.isDirectory()) {
                mAdapter.submitFile(file);
                check(file);
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putSerializable("currentDir", mCurrentFile);
    }
	
	private void openFile(File file) {
		Fragment parent = getParentFragment();
		
		if (parent != null) {
			if (parent instanceof MainFragment) {
				((MainFragment) parent).openFile(FileEditorManagerImpl.getInstance().openFile(requireContext(), file, true)[0]);
			}
		}
	}
    /**
     * Checks if the current file is equal to the root file if so,
     * it disables the OnBackPressedCallback
     */
    private void check(File currentFile) {
        mCurrentFile = currentFile;

        callback.setEnabled(!currentFile.getAbsolutePath().equals(mRootFile.getAbsolutePath()));
    }
}
```

### FileManagerAdapter

`app/src/main/java/com/tyron/code/ui/file/FileManagerAdapter.java`

```java
package com.tyron.code.ui.file;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FileManagerAdapter extends RecyclerView.Adapter<FileManagerAdapter.ViewHolder> {
    
    private static final int TYPE_NORMAL = 0;
    private static final int TYPE_BACK = 1;
    
    public interface OnItemClickListener {
        void onItemClick(File file, int position);
    }
    
    private OnItemClickListener mListener;
    private final List<File> mFiles = new ArrayList<>();
    
    public void setOnItemClickListener(OnItemClickListener listener) {
        mListener = listener;
    }
    
    public void submitFile(File file) {
        mFiles.clear();
        
        File[] files = file.listFiles();
        if (files != null) {
            mFiles.addAll(List.of(files));
        }
        
        Collections.sort(mFiles, (p1, p2) -> {
            if (p1.isFile() && p2.isFile()) {
                return p1.getName().compareTo(p2.getName());
            }

            if (p1.isFile() && p2.isDirectory()) {
                return -1;
            }

            if (p1.isDirectory() && p2.isDirectory()) {
                return p1.getName().compareTo(p2.getName());
            }
            return 0;
        });
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int p2) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.file_manager_item, parent, false);
        final ViewHolder holder = new ViewHolder(view);
        
        if (mListener != null) {
            view.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    File selected = null;
                    if (position != 0) {
                        selected = mFiles.get(position - 1);
                    }
                    mListener.onItemClick(selected, position);
                }
            });
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int p2) {
        int type = holder.getItemViewType();
        if (type == TYPE_BACK) {
            holder.bindBack();
        } else {
            holder.bind(mFiles.get(p2 - 1));
        }
    }

    @Override
    public int getItemCount() {
        return mFiles.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) {
            return TYPE_BACK;
        }
        return TYPE_NORMAL;
    }
    
    public static class ViewHolder extends RecyclerView.ViewHolder {
        
        public ImageView icon;
        public TextView name;
        
        public ViewHolder(View view) {
            super(view);
            
            icon = view.findViewById(R.id.icon);
            name = view.findViewById(R.id.name);
        }
        
        public void bind(File file) {
            name.setText(file.getName());
            
            if (file.isDirectory()) {
                icon.setImageResource(R.drawable.round_folder_24);
            } else if (file.isFile()) {
                icon.setImageResource(R.drawable.round_insert_drive_file_24);
            }
        }
        
        public void bindBack() {
            name.setText("...");
            icon.setImageResource(R.drawable.round_arrow_upward_24);
        }
    }
}
```
