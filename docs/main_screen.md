# CodeAssist 主界面实现与图标资源说明

本文件汇总了当前工程的主界面入口、关键布局文件以及相关图标资源，方便在 IDE 之外快速浏览。

## 1. 应用入口与启动 Activity

入口定义在 `app/src/main/AndroidManifest.xml` 中：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    package="com.tyron.code">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

    <application
        android:name=".ApplicationLoader"
        android:allowBackup="true"
        android:extractNativeLibs="true"
        android:hasFragileUserData="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:largeHeap="true"
        android:requestLegacyExternalStorage="true"
        android:resizeableActivity="true"
        android:supportsRtl="true"
        tools:targetApi="q">
        <service
            android:name=".service.GradleDaemonService"
            android:process=":gradleDaemonProcess"
            android:enabled="true"
            android:exported="false">
        </service>

        <activity
            android:name=".ui.settings.SettingsActivity"
            android:exported="false"
            android:label="@string/title_activity_settings"
            android:theme="@style/AppThemeNew" />
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/AppThemeNew"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.provider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/provider_paths" />
        </provider>
    </application>

</manifest>
```

- 启动 Activity：`com.tyron.code.MainActivity`
- 应用图标：`@mipmap/ic_launcher`（对应多分辨率 mipmap 资源，见第 4 节）

## 2. 主界面宿主 Activity：MainActivity

文件：`app/src/main/java/com/tyron/code/MainActivity.java`

```java
package com.tyron.code;

import android.os.Bundle;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.tyron.code.ui.project.ProjectManagerFragment;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (getSupportFragmentManager().findFragmentByTag(ProjectManagerFragment.TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new ProjectManagerFragment(),
                             ProjectManagerFragment.TAG)
                    .commit();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        return super.onKeyShortcut(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return super.onKeyUp(keyCode, event);
    }
}
```

要点：
- Activity 本身比较薄，仅负责：
  - 设置主布局 `R.layout.main`；
  - 把 `ProjectManagerFragment` 填充到 `fragment_container`；
  - 通过 `WindowCompat.setDecorFitsSystemWindows(getWindow(), false)` 实现沉浸式布局。

## 3. 主布局文件

### 3.1 Activity 布局：`app/src/main/res/layout/main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
	xmlns:android="http://schemas.android.com/apk/res/android"
	android:layout_width="match_parent"
	android:layout_height="match_parent"
	android:orientation="vertical">
   
	<androidx.fragment.app.FragmentContainerView
		android:layout_width="match_parent"
		android:layout_height="match_parent"
		android:id="@+id/fragment_container"/>

</LinearLayout>
```

- 该布局只有一个全屏的 `FragmentContainerView`，用来承载 `ProjectManagerFragment`。

### 3.2 主界面 Fragment 布局：`app/src/main/res/layout/project_manager_fragment.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.google.android.material.appbar.AppBarLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:fitsSystemWindows="true"
        app:liftOnScroll="true">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:title="@string/app_name" />

    </com.google.android.material.appbar.AppBarLayout>

    <androidx.core.widget.NestedScrollView
        android:id="@+id/scrolling_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior">

        <androidx.constraintlayout.widget.ConstraintLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content">

            <TextView
                android:id="@+id/whats_new"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="16dp"
                android:layout_marginTop="16dp"
                android:text="@string/project_manager_alpha_notice_title"
                android:textAppearance="@style/TextAppearance.Material3.TitleLarge"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toTopOf="parent" />

            <com.google.android.material.card.MaterialCardView
                android:id="@+id/header"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginStart="16dp"
                android:layout_marginTop="16dp"
                android:layout_marginEnd="16dp"
                app:cardCornerRadius="16dp"
                app:cardElevation="4dp"
                app:layout_constraintStart_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@+id/whats_new">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="16dp"
                    android:layout_marginTop="16dp"
                    android:layout_marginEnd="16dp"
                    android:layout_marginBottom="16dp"
                    android:autoLink="web"
                    android:linksClickable="true"
                    android:text="@string/project_manager_alpha_notice"
                    android:textAppearance="@style/TextAppearance.Material3.BodyMedium" />
            </com.google.android.material.card.MaterialCardView>


            <TextView
                android:id="@+id/title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="16dp"
                android:layout_marginTop="16dp"
                android:text="@string/project_manager_projects"
                android:textAppearance="@style/TextAppearance.Material3.TitleLarge"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/header" />

            <FrameLayout
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                app:layout_constraintBottom_toBottomOf="parent"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@+id/title"
                app:layout_constraintVertical_bias="0.0">

                <androidx.recyclerview.widget.RecyclerView
                    android:nestedScrollingEnabled="false"
                    android:id="@+id/projects_recycler"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="16dp"
                    android:layout_marginTop="16dp"
                    android:layout_marginEnd="16dp"
                    android:paddingBottom="55dp"
                    android:scrollbars="none"
                    tools:itemCount="21"
                    tools:listitem="@layout/project_item" />

                <include
                    android:id="@+id/empty_container"
                    layout="@layout/loading_layout"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />

                <include
                    android:id="@+id/empty_projects"
                    android:layout_gravity="center"
                    layout="@layout/empty_projects_layout"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent" />

            </FrameLayout>
        </androidx.constraintlayout.widget.ConstraintLayout>
    </androidx.core.widget.NestedScrollView>

    <com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
        android:id="@+id/create_project_fab"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="16dp"
        app:icon="@drawable/ic_baseline_add_24"
        app:layout_anchor="@+id/scrolling_view"
        app:layout_anchorGravity="bottom|end" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- 顶部 `AppBarLayout + MaterialToolbar` 显示应用名称；
- 中间 `RecyclerView` 展示项目列表：
  - 列表 item 使用布局 `@layout/project_item`；
  - 空列表时通过 `loading_layout` / `empty_projects_layout` 两个 include 切换；
- 底部右侧 `ExtendedFloatingActionButton` 使用 `@drawable/ic_baseline_add_24` 图标，作为“新建项目”入口。

### 3.3 项目列表项布局：`app/src/main/res/layout/project_item.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">

    <com.google.android.material.imageview.ShapeableImageView
        android:id="@+id/icon"
        android:layout_width="50dp"
        android:layout_height="50dp"
        android:layout_marginStart="8dp"
        android:layout_marginTop="8dp"
        android:layout_marginEnd="8dp"
        android:layout_marginBottom="8dp"
        android:src="@drawable/ic_launcher"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:shapeAppearance="@style/RoundedCorners"/>

    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:textSize="18sp"
        app:layout_constraintBottom_toBottomOf="@+id/icon"
        app:layout_constraintStart_toEndOf="@+id/icon"
        app:layout_constraintTop_toTopOf="@+id/icon"
        tools:text="Code Assist" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- 每个项目行左侧是一个圆角图标，使用 `@drawable/ic_launcher`。

## 4. 主界面相关图标资源

### 4.1 直接用于主界面的图标

- 应用图标（Launcher）：
  - `@mipmap/ic_launcher`
  - 资源文件：
    - `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
    - `app/src/main/res/mipmap-hdpi/ic_launcher.png`
    - `app/src/main/res/mipmap-mdpi/ic_launcher.png`
    - `app/src/main/res/mipmap-xhdpi/ic_launcher.png`
    - `app/src/main/res/mipmap-xxhdpi/ic_launcher.png`
    - `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- 项目列表项图标：
  - `@mipmap/ic_launcher`
  - TinaIDE 中统一使用矢量 launcher 图标（ic_launcher.webp）作为项目列表项的圆角缩略图。
- 新建项目 FAB 图标：
  - `@drawable/ic_baseline_add_24`
  - 资源文件：
    - `app/src/main/res/drawable/ic_baseline_add_24.xml`

### 4.2 其他可复用图标资源清单

以下图标当前不一定都在主界面使用，但可以在其他界面或将来设计中复用：

- `app/src/main/res/drawable/ic_baseline_check_box_24.xml`
- `app/src/main/res/drawable/ic_baseline_code_24.xml`
- `app/src/main/res/drawable/ic_baseline_crop_16_9_24.xml`
- `app/src/main/res/drawable/ic_baseline_edit_24.xml`
- `app/src/main/res/drawable/ic_baseline_edit_attributes_24.xml`
- `app/src/main/res/drawable/ic_baseline_folder_open_24.xml`
- `app/src/main/res/drawable/ic_baseline_format_line_spacing_24.xml`
- `app/src/main/res/drawable/ic_baseline_frame_24.xml`
- `app/src/main/res/drawable/ic_baseline_keyboard_arrow_down_24.xml`
- `app/src/main/res/drawable/ic_baseline_keyboard_arrow_right_24.xml`
- `app/src/main/res/drawable/ic_baseline_menu_24.xml`
- `app/src/main/res/drawable/ic_baseline_menu_book_24.xml`
- `app/src/main/res/drawable/ic_baseline_open_in_new_24.xml`
- `app/src/main/res/drawable/ic_baseline_style_24.xml`
- `app/src/main/res/drawable/ic_baseline_swipe_right_alt_24.xml`
- `app/src/main/res/drawable/ic_baseline_text_fields_24.xml`
- `app/src/main/res/drawable/ic_baseline_vertical_24.xml`
- `app/src/main/res/drawable/ic_icons8_discord.xml`
- `app/src/main/res/drawable/ic_icons8_telegram_app.xml`
- `app/src/main/res/drawable/ic_more_vert_black_20dp.xml`
- `app/src/main/res/drawable/ic_round_email_24.xml`
- `app/src/main/res/drawable/ic_round_info_24.xml`
- `app/src/main/res/drawable/ic_round_star_rate_24.xml`
- `app/src/main/res/drawable/ic_stat_code.xml`
- `app/src/main/res/drawable/round_arrow_upward_20.xml`
- `app/src/main/res/drawable/round_arrow_upward_24.xml`
- `app/src/main/res/drawable/round_content_copy_20.xml`
- `app/src/main/res/drawable/round_content_cut_20.xml`
- `app/src/main/res/drawable/round_content_paste_20.xml`
- `app/src/main/res/drawable/round_folder_20.xml`
- `app/src/main/res/drawable/round_folder_24.xml`
- `app/src/main/res/drawable/round_insert_drive_file_20.xml`
- `app/src/main/res/drawable/round_insert_drive_file_24.xml`
- `app/src/main/res/drawable/round_play_arrow_20.xml`
- `app/src/main/res/drawable/round_play_arrow_24.xml`
- `app/src/main/res/drawable/round_save_20.xml`
- `app/src/main/res/drawable/round_select_all_20.xml`
- `app/src/main/res/drawable/tab_indicator.xml`
- 额外的 box 图标：
  - `app/src/main/res/mipmap/box_red.png`

## 5. 主界面代码结构小结

- 启动 Activity：`MainActivity` 只负责容器和 Fragment 切换，遵循 SRP 原则；
- 主界面 UI 主要由 `ProjectManagerFragment` 及其布局 `project_manager_fragment.xml` 组成，包含：
  - 顶部工具栏
  - “新功能/提示”卡片
  - 项目列表和空态布局
  - 底部新建项目 FAB 按钮
- 图标资源集中在 `res/drawable*` 与 `res/mipmap*` 目录中，主界面仅使用其中一小部分，便于后续扩展：
  - 当前仅用到 `ic_launcher` 与 `ic_baseline_add_24` 两类主图标；
  - 其他图标预留给代码编辑、导航等功能，是对未来需求的合理扩展。

如果你希望把 `ProjectManagerFragment.java` 的完整源码也一并复制进文档，我可以在下一步继续补充一个“附录：Fragment 全量代码”章节。

## 6. 工程完整路径

- 当前工程根目录完整路径（Windows）：

  `C:\Users\wuxianggujun\CodeSpace\AndroidStudioProjects\CodeAssist`

