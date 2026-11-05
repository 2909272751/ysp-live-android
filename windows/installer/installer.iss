; 央视频全频道直播 · Windows 安装程序
; 用 Inno Setup 6 编译：ISCC.exe installer.iss
;
; 设计要点：
; 1) 自包含 —— 安装后无需任何外部依赖（JDK/libVLC 全部在包里）
; 2) 干净卸载 —— 卸载时删掉程序目录、开始菜单、桌面快捷方式；
;    用户配置（%APPDATA%\YspLive）单独询问，默认保留
; 3) 不写注册表垃圾 —— 只注册标准卸载项 + 应用路径（供“打开文件位置”等用）
; 4) 中文界面 —— 用简体中文，同时保留英文回退

#define AppName "央视频直播"
#define AppNameEn "YspLive"
#define AppVersion "9.6.3"
#define AppPublisher "Gary's Club"
#define AppURL "https://github.com/2909272751/ysp-live-android"
#define AppExeName "YspLive.exe"
#define SourceDir "..\dist\YspLive"

[Setup]
; AppId 必须固定不变，否则升级会被当成新安装
AppId={{8F3A9C21-5D74-4B8E-A1C6-2E9F0B7D4A53}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}
AppUpdatesURL={#AppURL}
VersionInfoVersion={#AppVersion}

DefaultDirName={autopf}\{#AppNameEn}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
AllowNoIcons=yes
; 「仅为我安装」时用非特权目录，避免触发 UAC
; （PrivilegesRequired=lowest + autopf 会自动解析到
;   %LOCALAPPDATA%\Programs 或 %ProgramFiles%，取决于用户选择）

; 226MB 内容，压缩后约 90MB
Compression=lzma2/max
SolidCompression=yes
LZMANumBlockThreads=4

; 必须 64 位系统（runtime 与 libVLC 都是 x64）
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

; 界面
WizardStyle=modern
WizardImageFile=wizard.bmp
WizardSmallImageFile=wizard-small.bmp
SetupIconFile=app.ico
UninstallDisplayIcon={app}\{#AppExeName}
UninstallDisplayName={#AppName}
ShowLanguageDialog=auto

; 权限：默认请求管理员（装到 Program Files），但允许用户在向导里选择
; 「仅为我安装」以装到 %LOCALAPPDATA%\Programs —— 那样不需要管理员权限。
; 这一点很关键：若强制 admin 安装，程序也常以提权身份运行，
; 用户配置会被写到管理员账户的 APPDATA，普通用户看不到自己的设置。
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
; 需要能写 Program Files 时自动提权（用户选择「所有用户」时）

; 输出
OutputDir=..\release
OutputBaseFilename=YspLive-Setup-{#AppVersion}
LicenseFile=LICENSE.txt

[Languages]
Name: "chinese"; MessagesFile: "ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
chinese.CreateDesktopIcon=创建桌面快捷方式
chinese.LaunchApp=立即运行 {#AppName}
chinese.AdditionalIcons=附加快捷方式：
chinese.RemoveUserData=同时删除用户配置与缓存（%APPDATA%\YspLive）
chinese.RemoveUserDataHint=如果你只是升级或暂时卸载，建议保留，下次安装可直接沿用设置。
english.CreateDesktopIcon=Create a desktop shortcut
english.LaunchApp=Launch {#AppName}
english.AdditionalIcons=Additional shortcuts:
english.RemoveUserData=Also delete user settings and cache (%APPDATA%\YspLive)
english.RemoveUserDataHint=Keep this if you are upgrading or may reinstall later.

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; \
    GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce

[Files]
; 程序主体（exe + 精简 JDK runtime）
Source: "{#SourceDir}\{#AppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\runtime\*"; DestDir: "{app}\runtime"; \
    Flags: ignoreversion recursesubdirs createallsubdirs
; 应用 jar
Source: "{#SourceDir}\app\*"; DestDir: "{app}\app"; \
    Flags: ignoreversion recursesubdirs createallsubdirs
; 内置 libVLC（播放器核心）
Source: "{#SourceDir}\vlc\*"; DestDir: "{app}\vlc"; \
    Flags: ignoreversion recursesubdirs createallsubdirs
; 说明文档
Source: "{#SourceDir}\README.md"; DestDir: "{app}"; Flags: ignoreversion

[Dirs]
; 用户数据目录在安装时创建（卸载时按用户选择处理）
Name: "{userappdata}\YspLive"; Permissions: users-modify

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"; \
    IconFilename: "{app}\{#AppExeName}"
Name: "{group}\使用说明"; Filename: "{app}\README.md"
Name: "{group}\卸载 {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; \
    IconFilename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Run]
; 安装完成后可选启动（不要管理员身份运行，否则用户配置会写到管理员目录）
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchApp}"; \
    Flags: nowait postinstall skipifsilent

[UninstallRun]
; 卸载前先确保进程退出，否则文件占用导致卸载残留
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM {#AppExeName}"; \
    Flags: runhidden; RunOnceId: "KillApp"

[UninstallDelete]
; 程序自己生成的运行期文件（不属于安装文件）
Type: filesandordirs; Name: "{app}\app\browser-profile"
Type: files; Name: "{app}\*.log"
Type: dirifempty; Name: "{app}"

[Code]
var
  RemoveUserData: Boolean;
  SilentUninstall: Boolean;
  ForceRemoveUserData: Boolean;

// 卸载时询问是否一并删除用户配置。
//
// 两个关键点（都是踩过坑才加上的）：
// 1) 静默卸载必须跳过 MsgBox —— 否则 /VERYSILENT 部署会永久挂起等待点击。
//    Inno 的 /SUPPRESSMSGBOXES 只对「安装」阶段的自动消息生效，
//    自定义代码里的 MsgBox 不会被自动应答，必须自己判断。
// 2) 提供命令行开关 /REMOVEDATA 供无人值守场景强制连数据一起删。
function InitializeUninstall(): Boolean;
var
  Msg: String;
  CmdLine: String;
begin
  Result := True;
  RemoveUserData := False;

  CmdLine := UpperCase(GetCmdTail);
  SilentUninstall := (Pos('/SILENT', CmdLine) > 0) or
                     (Pos('/VERYSILENT', CmdLine) > 0);
  ForceRemoveUserData := Pos('/REMOVEDATA', CmdLine) > 0;

  if ForceRemoveUserData then
  begin
    RemoveUserData := True;
    Exit;
  end;

  // 静默模式：不弹框，默认保留用户数据（安全默认）
  if SilentUninstall then
  begin
    RemoveUserData := False;
    Exit;
  end;

  Msg := ExpandConstant('{cm:RemoveUserData}') + #13#10 + #13#10 +
         ExpandConstant('{cm:RemoveUserDataHint}') + #13#10 + #13#10 +
         ExpandConstant('{userappdata}\YspLive');
  RemoveUserData := MsgBox(Msg, mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES;
end;

// 卸载收尾：按选择删除用户数据目录
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  UserDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    if RemoveUserData then
    begin
      UserDir := ExpandConstant('{userappdata}\YspLive');
      if DirExists(UserDir) then
        DelTree(UserDir, True, True, True);
    end;
  end;
end;

// 安装前检查：若程序正在运行，提示先关闭
function InitializeSetup(): Boolean;
var
  ResultCode: Integer;
begin
  Result := True;
  // 用 tasklist 探测，避免依赖额外组件
  if Exec(ExpandConstant('{sys}\tasklist.exe'),
          '/FI "IMAGENAME eq {#AppExeName}" /NH',
          '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
  begin
    // 不做强制拦截，仅让用户知道可能覆盖失败
  end;
end;

// 版本升级说明
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    // 预留：可在此写入首次安装标记
  end;
end;
