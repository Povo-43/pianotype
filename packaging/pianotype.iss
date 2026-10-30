[Setup]
; CI から /DVersion /DTag /DBin /を渡してビルドする
AppId={{7B4C2E90-57A1-4F63-B25B-3C1D5E8A9F41}
AppName=PianoType
AppVersion={#Version}
AppPublisher=Povo-43
AppPublisherURL=https://github.com/Povo-43/pianotype
DefaultDirName={autopf}\PianoType
DefaultGroupName=PianoType
OutputDir=dist
OutputBaseFilename=pianotype-pc-{#Tag}-windows-x64-installer
UninstallDisplayName=PianoType
UninstallDisplayIcon={app}\pianotype.exe
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible

[Files]
Source: "{#Bin}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\PianoType"; Filename: "{app}\pianotype.exe"
Name: "{group}\Uninstall PianoType"; Filename: "{uninstallexe}"
