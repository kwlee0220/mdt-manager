package mdt.instance.jar;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.InternalException;
import utils.async.command.CommandExecution;
import utils.io.FileUtils;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class PythonVenvCreator {
	private static final Logger s_logger = LoggerFactory.getLogger(PythonVenvCreator.class);
	private static final int TIMEOUT_SECONDS = 120;
	private static final Duration TIMEOUT_VENV = Duration.ofSeconds(30);
	private static final Duration TIMEOUT_PIP = Duration.ofSeconds(120);
	
	public static void createVenv(File tarDir) throws CancellationException, InterruptedException, ExecutionException {
		CommandExecution exec = CommandExecution.builder()
												.addCommand("python3", "-m", "venv", "venv")
						                        .workingDirectory(tarDir)
						                        .timeout(TIMEOUT_VENV)
						                        .inheritStdout()
						                        .inheritStderr()
						                        .build();
		exec.run();
	}
	
	public static void installRequirements(File tarDir, File reqsFile)
		throws CancellationException, InterruptedException, ExecutionException {
		String python3Path = FileUtils.path(tarDir, "venv", "bin", "python3").getAbsolutePath();
		CommandExecution exec = CommandExecution.builder()
												.addCommand(python3Path, "-m", "pip", "install", "-r",
															reqsFile.getAbsolutePath())
						                        .workingDirectory(tarDir)
						                        .timeout(TIMEOUT_PIP)
						                        .inheritStdout()
						                        .inheritStderr()
						                        .build();
		exec.run();
	}

	 public static void create(File tarDir) throws IOException, InterruptedException, InternalException {
		if ( tarDir == null || !tarDir.isDirectory() ) {
			throw new IllegalArgumentException("target directory must be an existing directory: " + tarDir);
		}

	     File venvDir = new File(tarDir, "venv");
	     if (venvDir.exists()) {
	         s_logger.info("Virtualenv already exists at {}", venvDir.getAbsolutePath());
	         return;
	     }

	     ProcessBuilder pb = new ProcessBuilder("python3", "-m", "venv", "venv");
	     pb.directory(tarDir);
	     pb.redirectErrorStream(true);

	     Process p = pb.start();

	     ExecutorService logExec = Executors.newSingleThreadExecutor();
	     Future<?> logFuture = logExec.submit(() -> {
	         try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
	             String line;
	             while ((line = r.readLine()) != null) {
	                 s_logger.info("[venv] {}", line);
	             }
	         } catch (IOException e) {
	             s_logger.debug("Failed to read venv process output", e);
	         }
	     });

		boolean finished = false;
		try {
			finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			// ensure logger thread finishes quickly
			try {
				logFuture.get(1, TimeUnit.SECONDS);
			}
			catch ( Exception ignore ) {
			}
		}
		finally {
			logExec.shutdownNow();
		}

		if ( !finished ) {
			p.destroyForcibly();
			throw new IOException("Timeout while creating python venv in " + tarDir.getAbsolutePath());
		}

	     int rc = p.exitValue();
	     if (rc != 0) {
	         throw new IOException("python3 -m venv exited with code " + rc + " (see logs for details)");
	     }

	     s_logger.info("Created python venv at {}", venvDir.getAbsolutePath());
	 }
}
