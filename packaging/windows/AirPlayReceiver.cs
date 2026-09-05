// Native no-console launcher for the self-contained Java AirPlay Windows distribution.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Forms;

namespace JavaAirPlayLauncher
{
    internal static class Program
    {
        private const string ValidationArgument = "--validate-installation";
        private const string JarFileName = "java-airplay-server.jar";
        private const int ValidationTimeoutMilliseconds = 60000;

        [STAThread]
        private static int Main(string[] args)
        {
            bool validationOnly = ContainsArgument(args, ValidationArgument);
            string validationFileBasePath = null;
            try
            {
                string appDirectory = Path.GetFullPath(AppDomain.CurrentDomain.BaseDirectory);
                string validationError = ValidateInstallation(appDirectory);
                if (validationError != null)
                {
                    if (!validationOnly)
                    {
                        ShowError(validationError);
                    }
                    return 2;
                }

                if (validationOnly)
                {
                    validationFileBasePath = Path.Combine(
                        Path.GetTempPath(),
                        "Java AirPlay Validation " + Guid.NewGuid().ToString("N"));
                }
                ProcessStartInfo startInfo = CreateStartInfo(
                    appDirectory, args, validationFileBasePath);
                validationError = ValidateStartInfo(appDirectory, startInfo, validationFileBasePath);
                if (validationError != null)
                {
                    if (!validationOnly)
                    {
                        ShowError(validationError);
                    }
                    return 3;
                }
                if (validationOnly)
                {
                    return RunJavaValidation(startInfo);
                }

                Process process = Process.Start(startInfo);
                if (process == null)
                {
                    ShowError("Windows did not return a Java process handle.");
                    return 4;
                }
                process.Dispose();
                return 0;
            }
            catch (Exception error)
            {
                if (!validationOnly)
                {
                    ShowError("Unable to start the bundled Java runtime.\n\n" + error.Message);
                }
                return 5;
            }
            finally
            {
                if (validationFileBasePath != null)
                {
                    DeleteFile(validationFileBasePath + ".properties");
                    DeleteFile(validationFileBasePath + ".key");
                    DeleteFile(validationFileBasePath + ".log");
                    DeleteFile(validationFileBasePath + ".gst-registry.bin");
                }
            }
        }

        private static ProcessStartInfo CreateStartInfo(
            string appDirectory,
            string[] args,
            string validationFileBasePath)
        {
            string runtimeBin = Path.Combine(appDirectory, "runtime", "bin");
            string javaExecutable = Path.Combine(runtimeBin, "javaw.exe");
            string gstreamerRoot = Path.Combine(appDirectory, ".runtime", "gstreamer");
            string gstreamerBin = Path.Combine(gstreamerRoot, "bin");
            string gstreamerPlugins = Path.Combine(gstreamerRoot, "lib", "gstreamer-1.0");
            string gstreamerScanner = Path.Combine(
                gstreamerRoot, "libexec", "gstreamer-1.0", "gst-plugin-scanner.exe");
            string jarPath = Path.Combine(appDirectory, JarFileName);

            var javaArguments = new List<string>
            {
                "--enable-native-access=ALL-UNNAMED",
                "-Dfile.encoding=UTF-8",
                "-Dgstreamer.path=" + gstreamerBin
            };
            if (validationFileBasePath != null)
            {
                javaArguments.Add(
                    "-Djava-airplay.settings-file=" + validationFileBasePath + ".properties");
            }
            javaArguments.Add("-jar");
            javaArguments.Add(jarPath);

            if (validationFileBasePath != null)
            {
                javaArguments.Add("--java-airplay.validation=true");
                javaArguments.Add("--player.implementation=gstreamer");
                javaArguments.Add("--player.gstreamer.swing=false");
                javaArguments.Add("--player.tray.enabled=false");
                javaArguments.Add("--airplay.identityFile=" + validationFileBasePath + ".key");
                javaArguments.Add("--logging.file.name=" + validationFileBasePath + ".log");
            }
            else
            {
                foreach (string argument in args)
                {
                    javaArguments.Add(argument);
                }
            }

            var startInfo = new ProcessStartInfo
            {
                FileName = javaExecutable,
                Arguments = BuildCommandLine(javaArguments),
                WorkingDirectory = appDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
                ErrorDialog = false
            };

            string existingPath = startInfo.EnvironmentVariables["PATH"] ?? String.Empty;
            startInfo.EnvironmentVariables["PATH"] = JoinPathEntries(
                runtimeBin, gstreamerBin, existingPath);
            startInfo.EnvironmentVariables["GST_PLUGIN_PATH"] = gstreamerPlugins;
            startInfo.EnvironmentVariables["GST_PLUGIN_PATH_1_0"] = gstreamerPlugins;
            startInfo.EnvironmentVariables["GST_PLUGIN_SYSTEM_PATH"] = gstreamerPlugins;
            startInfo.EnvironmentVariables["GST_PLUGIN_SYSTEM_PATH_1_0"] = gstreamerPlugins;
            startInfo.EnvironmentVariables["GST_PLUGIN_SCANNER"] = gstreamerScanner;
            startInfo.EnvironmentVariables["GST_PLUGIN_SCANNER_1_0"] = gstreamerScanner;
            if (validationFileBasePath != null)
            {
                startInfo.EnvironmentVariables["GST_REGISTRY_1_0"] =
                    validationFileBasePath + ".gst-registry.bin";
            }
            return startInfo;
        }

        private static string ValidateInstallation(string appDirectory)
        {
            string runtimeBin = Path.Combine(appDirectory, "runtime", "bin");
            string gstreamerRoot = Path.Combine(appDirectory, ".runtime", "gstreamer");
            string javaExecutable = Path.Combine(runtimeBin, "javaw.exe");
            string jarPath = Path.Combine(appDirectory, JarFileName);
            string gstreamerBin = Path.Combine(gstreamerRoot, "bin");
            string gstreamerInspect = Path.Combine(gstreamerBin, "gst-inspect-1.0.exe");
            string gstreamerPlugins = Path.Combine(gstreamerRoot, "lib", "gstreamer-1.0");
            string gstreamerScanner = Path.Combine(
                gstreamerRoot, "libexec", "gstreamer-1.0", "gst-plugin-scanner.exe");

            if (!File.Exists(javaExecutable))
            {
                return "Bundled Java runtime was not found:\n" + javaExecutable;
            }
            if (!File.Exists(jarPath))
            {
                return "Java AirPlay server was not found:\n" + jarPath;
            }
            if (!File.Exists(gstreamerInspect))
            {
                return "Bundled GStreamer runtime was not found:\n" + gstreamerInspect;
            }
            if (!Directory.Exists(gstreamerPlugins))
            {
                return "Bundled GStreamer plugin directory was not found:\n" + gstreamerPlugins;
            }
            if (!File.Exists(gstreamerScanner))
            {
                return "Bundled GStreamer plugin scanner was not found:\n" + gstreamerScanner;
            }
            return null;
        }

        private static string ValidateStartInfo(
            string appDirectory,
            ProcessStartInfo startInfo,
            string validationFileBasePath)
        {
            string runtimeBin = Path.Combine(appDirectory, "runtime", "bin");
            string gstreamerRoot = Path.Combine(appDirectory, ".runtime", "gstreamer");
            string gstreamerBin = Path.Combine(gstreamerRoot, "bin");
            string jarPath = Path.Combine(appDirectory, JarFileName);
            if (!String.Equals(
                    startInfo.FileName,
                    Path.Combine(runtimeBin, "javaw.exe"),
                    StringComparison.OrdinalIgnoreCase))
            {
                return "Launcher validation failed: javaw.exe path is incorrect.";
            }
            bool hasRequiredArguments =
                startInfo.Arguments.IndexOf(
                    "--enable-native-access=ALL-UNNAMED", StringComparison.Ordinal) >= 0
                && startInfo.Arguments.IndexOf("-Dgstreamer.path=", StringComparison.Ordinal) >= 0
                && startInfo.Arguments.IndexOf(
                    QuoteArgument(jarPath), StringComparison.Ordinal) >= 0;
            if (!hasRequiredArguments)
            {
                return "Launcher validation failed: required JVM arguments are missing.";
            }
            if (validationFileBasePath != null)
            {
                foreach (string requiredArgument in new[]
                {
                    "-Djava-airplay.settings-file=",
                    "--java-airplay.validation=true",
                    "--player.implementation=gstreamer",
                    "--player.gstreamer.swing=false",
                    "--player.tray.enabled=false",
                    "--airplay.identityFile=",
                    "--logging.file.name="
                })
                {
                    if (startInfo.Arguments.IndexOf(requiredArgument, StringComparison.Ordinal) < 0)
                    {
                        return "Launcher validation failed: Java startup probe arguments are missing.";
                    }
                }

                if (!String.Equals(
                        startInfo.EnvironmentVariables["GST_REGISTRY_1_0"],
                        validationFileBasePath + ".gst-registry.bin",
                        StringComparison.OrdinalIgnoreCase))
                {
                    return "Launcher validation failed: isolated GStreamer registry is missing.";
                }
            }
            string processPath = startInfo.EnvironmentVariables["PATH"] ?? String.Empty;
            string requiredPathPrefix = runtimeBin + ";" + gstreamerBin;
            bool hasRequiredPath =
                String.Equals(processPath, requiredPathPrefix, StringComparison.OrdinalIgnoreCase)
                || processPath.StartsWith(
                    requiredPathPrefix + ";", StringComparison.OrdinalIgnoreCase);
            if (!hasRequiredPath)
            {
                return "Launcher validation failed: bundled runtime directories are missing from PATH.";
            }
            string pluginPath = startInfo.EnvironmentVariables["GST_PLUGIN_PATH"] ?? String.Empty;
            string requiredPluginPath = Path.Combine(gstreamerRoot, "lib", "gstreamer-1.0");
            if (!String.Equals(pluginPath, requiredPluginPath, StringComparison.OrdinalIgnoreCase))
            {
                return "Launcher validation failed: bundled GStreamer plugins are missing from the environment.";
            }
            string scannerPath = startInfo.EnvironmentVariables["GST_PLUGIN_SCANNER_1_0"] ?? String.Empty;
            string requiredScannerPath = Path.Combine(
                gstreamerRoot, "libexec", "gstreamer-1.0", "gst-plugin-scanner.exe");
            if (!String.Equals(scannerPath, requiredScannerPath, StringComparison.OrdinalIgnoreCase))
            {
                return "Launcher validation failed: bundled GStreamer scanner is missing from the environment.";
            }
            return null;
        }

        private static int RunJavaValidation(ProcessStartInfo startInfo)
        {
            using (Process process = Process.Start(startInfo))
            {
                if (process == null)
                {
                    return 4;
                }
                if (process.WaitForExit(ValidationTimeoutMilliseconds))
                {
                    return process.ExitCode;
                }

                try
                {
                    process.Kill();
                    process.WaitForExit(5000);
                }
                catch
                {
                    // Best effort cleanup after a validation timeout.
                }
                return 6;
            }
        }

        private static string BuildCommandLine(IEnumerable<string> arguments)
        {
            var commandLine = new StringBuilder();
            foreach (string argument in arguments)
            {
                if (commandLine.Length > 0)
                {
                    commandLine.Append(' ');
                }
                commandLine.Append(QuoteArgument(argument));
            }
            return commandLine.ToString();
        }

        private static string QuoteArgument(string argument)
        {
            if (argument.Length == 0)
            {
                return "\"\"";
            }

            bool requiresQuotes = false;
            foreach (char character in argument)
            {
                if (Char.IsWhiteSpace(character) || character == '"')
                {
                    requiresQuotes = true;
                    break;
                }
            }
            if (!requiresQuotes)
            {
                return argument;
            }

            var quotedArgument = new StringBuilder();
            quotedArgument.Append('"');
            int backslashCount = 0;
            foreach (char character in argument)
            {
                if (character == '\\')
                {
                    backslashCount++;
                }
                else if (character == '"')
                {
                    quotedArgument.Append('\\', backslashCount * 2 + 1);
                    quotedArgument.Append('"');
                    backslashCount = 0;
                }
                else
                {
                    quotedArgument.Append('\\', backslashCount);
                    quotedArgument.Append(character);
                    backslashCount = 0;
                }
            }
            quotedArgument.Append('\\', backslashCount * 2);
            quotedArgument.Append('"');
            return quotedArgument.ToString();
        }

        private static string JoinPathEntries(params string[] values)
        {
            var path = new StringBuilder();
            foreach (string value in values)
            {
                if (String.IsNullOrEmpty(value))
                {
                    continue;
                }
                if (path.Length > 0)
                {
                    path.Append(';');
                }
                path.Append(value);
            }
            return path.ToString();
        }

        private static void DeleteFile(string path)
        {
            try
            {
                File.Delete(path);
            }
            catch
            {
                // Validation cleanup must not hide the Java process result.
            }
        }

        private static bool ContainsArgument(IEnumerable<string> arguments, string expected)
        {
            foreach (string argument in arguments)
            {
                if (String.Equals(argument, expected, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }

        private static void ShowError(string message)
        {
            MessageBox.Show(
                "Java AirPlay could not start.\nJava AirPlay 无法启动。\n\n" + message,
                "Java AirPlay Receiver",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        }
    }
}
