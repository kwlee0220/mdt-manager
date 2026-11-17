package mdt.instance.jar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/**
 * {@code uv_projects.txt}의 경로가 인스턴스 디렉터리 밖을 가리키지 못하는지 확인한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class PythonUvProjectsTest {
	@TempDir File m_tmp;

	@Test
	public void syncFailuresAreReportedNotSwallowed() throws Exception {
		File base = baseDir();
		File list = new File(base, "uv_projects.txt");
		
		// 목록 파일이 없다.
		assertThrows(IOException.class, () -> PythonUvProjects.syncProjectsAll(list));
		
		// 인스턴스 디렉터리를 벗어나는 경로.
		Files.writeString(list.toPath(), "../outside\n");
		assertThrows(IOException.class, () -> PythonUvProjects.syncProjectsAll(list));
		
		// pyproject.toml이 없는 프로젝트. uv를 실행하기 전에 실패한다.
		new File(base, "py").mkdirs();
		Files.writeString(list.toPath(), "py\n");
		assertThrows(IOException.class, () -> PythonUvProjects.syncProjectsAll(list));
		
		// 빈 줄만 있으면 할 일이 없다.
		Files.writeString(list.toPath(), "\n  \n");
		PythonUvProjects.syncProjectsAll(list);
	}

	@Test
	public void pathsInsideTheInstanceDirectoryAreResolved() throws Exception {
		File base = baseDir();
		new File(base, "py/app").mkdirs();
		assertEquals(new File(base, "py/app").getCanonicalFile(), PythonUvProjects.resolveProjectDir(base, "py/app"));
		assertEquals(base, PythonUvProjects.resolveProjectDir(base, "."));
		assertEquals(new File(base, "py").getCanonicalFile(),
					PythonUvProjects.resolveProjectDir(base, new File(base, "py").getAbsolutePath()));
	}

	@Test
	public void pathsEscapingTheInstanceDirectoryAreRejected() throws Exception {
		File base = baseDir();
		assertNull(PythonUvProjects.resolveProjectDir(base, ".."));
		assertNull(PythonUvProjects.resolveProjectDir(base, "../other"));
		assertNull(PythonUvProjects.resolveProjectDir(base, "py/../../other"));
		assertNull(PythonUvProjects.resolveProjectDir(base, m_tmp.getAbsolutePath()));
		assertNull(PythonUvProjects.resolveProjectDir(base, "/tmp"));
		// 'inst'로 시작하는 형제 디렉터리('inst2')도 밖이다.
		new File(m_tmp, "inst2").mkdirs();
		assertNull(PythonUvProjects.resolveProjectDir(base, "../inst2"));
	}

	@Test
	public void symlinkPointingOutsideIsRejected() throws Exception {
		File base = baseDir();
		File outside = new File(m_tmp, "outside");
		outside.mkdirs();
		Files.createSymbolicLink(new File(base, "link").toPath(), outside.toPath());
		assertNull(PythonUvProjects.resolveProjectDir(base, "link"));
	}

	@Test
	public void venvSymlinkIsRemovedWithoutTouchingItsTarget() throws Exception {
		File base = baseDir();
		File target = new File(m_tmp, "precious");
		target.mkdirs();
		Files.writeString(new File(target, "data.txt").toPath(), "keep");
		Files.createSymbolicLink(new File(base, ".venv").toPath(), target.toPath());

		PythonUvProjects.removeVirtualEnv(base);
		assertFalse(Files.exists(new File(base, ".venv").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
		assertTrue(new File(target, "data.txt").isFile());
	}

	private File baseDir() throws Exception {
		File base = new File(m_tmp, "inst");
		base.mkdirs();
		return base.getCanonicalFile();
	}
}
