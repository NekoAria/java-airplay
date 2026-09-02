#ifndef AppVersion
  #error AppVersion must be supplied by Gradle
#endif
#ifndef StageDir
  #error StageDir must be supplied by Gradle
#endif
#ifndef OutputDir
  #error OutputDir must be supplied by Gradle
#endif
#ifndef IconFile
  #error IconFile must be supplied by Gradle
#endif

#define AppName "Java AirPlay Receiver"
#define AppPublisher "Arc-Lira"
#define AppExeName "JavaAirPlayReceiver.exe"
#define AppUrl "https://github.com/Arc-Lira/java-airplay"

[Setup]
AppId={{37450B36-B7C9-4588-881E-F7F18F0E6BC1}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppUrl}
AppSupportURL={#AppUrl}/issues
AppUpdatesURL={#AppUrl}/releases
AppComments=AirPlay screen mirroring receiver with a bundled Java and GStreamer runtime.
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
AllowNoIcons=yes
LicenseFile={#StageDir}\LICENSE
OutputDir={#OutputDir}
OutputBaseFilename=java-airplay-{#AppVersion}-windows-x64-setup
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\{#AppExeName}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
LZMADictionarySize=65536
WizardStyle=modern
PrivilegesRequired=admin
MinVersion=10.0
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UsePreviousAppDir=yes
CloseApplications=yes
RestartApplications=no
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[CustomMessages]
english.FirewallHint=Installation is complete.%n%nOn first launch, allow Java AirPlay through Windows Firewall on private networks. Otherwise an iPhone or iPad may not discover this PC.%n%nApplication settings are stored in your user profile and are preserved when the application is uninstalled.
chinesesimp.FirewallHint=安装已完成。%n%n首次启动时，请允许 Java AirPlay 通过 Windows 防火墙的专用网络访问，否则 iPhone 或 iPad 可能无法发现此电脑。%n%n应用设置保存在当前用户目录中，卸载程序时会继续保留。

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#StageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#AppName}}"; WorkingDir: "{app}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and (not WizardSilent()) then
    MsgBox(ExpandConstant('{cm:FirewallHint}'), mbInformation, MB_OK);
end;
