package mdt.instance.jar;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.async.command.CommandExecution;
import utils.async.command.CommandExecution.Output;
import utils.io.FileUtils;

/**
 * Python {@code uv} 패키지 매니저로 관리되는 프로젝트의 의존성을 동기화하는 유틸리티.
 * <p>
 * 외부 명령 {@code uv sync --no-dev}를 실행하여 {@code pyproject.toml}에 명시된 의존성을 설치한다.
 * 단일 프로젝트는 {@link #syncProject(File)}로, 여러 프로젝트는 디렉터리 경로 목록 파일을 받아
 * 일괄 처리하는 {@link #syncProjectsAll(File)}로 동기화한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class PythonUvProjects {
	private static final Logger s_logger = LoggerFactory.getLogger(PythonUvProjects.class);
	private static final Duration TIMEOUT_SYNC_UV_PROJECT = Duration.ofSeconds(120);

	private PythonUvProjects() {}

	/**
	 * 프로젝트 디렉터리 경로 목록 파일을 읽어, 각 프로젝트를 순차적으로 동기화한다.
	 * <p>
	 * 목록 파일은 한 줄당 하나의 프로젝트 디렉터리 경로를 담는다. 빈 줄은 무시하고,
	 * 상대 경로는 목록 파일의 부모 디렉터리를 기준으로 해석한다. 목록 파일은 업로드된 번들의 일부이므로,
	 * 해석한 경로(심볼릭 링크를 따라간 실제 경로)가 목록 파일의 부모 디렉터리(인스턴스 디렉터리) 자신이거나
	 * 그 아래가 아니면 거부한다.
	 * <p>
	 * 하나라도 실패하면 곧바로 멈추고 {@link IOException}을 던진다. 그래서 Python 환경이
	 * 없는 인스턴스는 등록되지 않는다.
	 *
	 * @param projectListFile 프로젝트 경로 목록 파일.
	 * @throws IOException	목록 파일을 읽지 못했거나, 경로가 인스턴스 디렉터리를 벗어나거나, 동기화가 실패한 경우.
	 */
	public static void syncProjectsAll(File projectListFile) throws IOException {
		File baseDir = projectListFile.getAbsoluteFile().getParentFile().getCanonicalFile();
		for ( String line: Files.readAllLines(projectListFile.toPath(), StandardCharsets.UTF_8) ) {
			String projectDirPath = line.trim();
			if ( projectDirPath.isEmpty() ) {
				continue;
			}
			
			File projectDir = resolveProjectDir(baseDir, projectDirPath);
			if ( projectDir == null ) {
				throw new IOException(String.format("uv project is outside the instance directory: path=%s, base=%s",
													projectDirPath, baseDir));
			}
			try {
				s_logger.info("Syncing project at {}", projectDir.getAbsolutePath());
				syncProject(projectDir);
			}
			catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("interrupted while syncing uv project: " + projectDir);
			}
			catch ( ExecutionException | CancellationException | IllegalArgumentException e ) {
				throw new IOException("Failed to sync uv project: " + projectDir, e);
			}
		}
	}

	/**
	 * 목록 파일의 한 줄(프로젝트 경로)을 {@code baseDir} 기준으로 해석한다.
	 *
	 * @param baseDir	기준 디렉터리(canonical).
	 * @param path		목록 파일의 경로 문자열(상대 또는 절대).
	 * @return	해석한 canonical 디렉터리. {@code baseDir} 자신이거나 그 아래가 아니면 {@code null}.
	 * @throws IOException	canonical 경로를 구할 수 없는 경우.
	 */
	static File resolveProjectDir(File baseDir, String path) throws IOException {
		File dir = new File(path);
		if ( !dir.isAbsolute() ) {
			dir = new File(baseDir, path);
		}
		File canonical = dir.getCanonicalFile();
		return canonical.toPath().startsWith(baseDir.toPath()) ? canonical : null;
	}

	/**
	 * 주어진 프로젝트 디렉터리에서 {@code uv sync --no-dev}를 실행하여 의존성을 동기화한다.
	 * <p>
	 * 디렉터리에 {@code pyproject.toml}이 없으면 {@link IllegalArgumentException}을 던진다.
	 * 외부 명령 실행은 최대 120초의 제한 시간을 가지며, 표준 출력/오류는 현재 프로세스의
	 * 출력으로 그대로 전달된다.
	 *
	 * @param projectDir 대상 프로젝트 디렉터리.
	 * @throws IllegalArgumentException {@code pyproject.toml}이 존재하지 않는 경우.
	 * @throws CancellationException	명령 실행이 취소된 경우.
	 * @throws InterruptedException	명령 실행 대기 중 쓰레드가 인터럽트된 경우.
	 * @throws ExecutionException	명령 실행 중 오류가 발생한 경우.
	 */
	public static void syncProject(File projectDir) throws CancellationException, InterruptedException,
																ExecutionException {
		File pyprojectFile = new File(projectDir, "pyproject.toml");
		if ( !pyprojectFile.isFile() ) {
			throw new IllegalArgumentException("pyproject.toml file not found in "
												+ projectDir.getAbsolutePath());
		}
		
		// 이미 가상 환경이 존재하는 경우에는 제거한다.
		try {
			removeVirtualEnv(projectDir);
		}
		catch ( IOException e ) {
			throw new ExecutionException("Failed to remove existing virtual environment in "
										+ projectDir.getAbsolutePath(), e);
		}

		CommandExecution exec = CommandExecution.builder()
												.addCommand("uv", "sync", "--no-dev")
												.workingDirectory(projectDir)
												.timeout(TIMEOUT_SYNC_UV_PROJECT)
												.stdout(Output.inherit())
												.stderr(Output.inherit())
												.build();
		s_logger.info("Syncing uv project in {}", projectDir.getAbsolutePath());
		exec.run();
	}
	
	public static void removeVirtualEnv(File projectDir) throws IOException {
		File uvEnvDir = new File(projectDir, ".venv");
		// .venv가 심볼릭 링크면 링크만 지운다(가리키는 다른 디렉터리를 지우지 않도록).
		if ( Files.isSymbolicLink(uvEnvDir.toPath()) ) {
			s_logger.info("Removing virtual environment link at {}", uvEnvDir.getAbsolutePath());
			Files.delete(uvEnvDir.toPath());
		}
		else if ( uvEnvDir.isDirectory() ) {
			s_logger.info("Removing virtual environment at {}", uvEnvDir.getAbsolutePath());
			FileUtils.deleteDirectory(uvEnvDir);
		}
	}
}
