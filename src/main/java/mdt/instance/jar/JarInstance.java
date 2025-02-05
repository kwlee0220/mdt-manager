package mdt.instance.jar;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mdt.exector.jar.JarInstanceExecutor;
import mdt.instance.JpaInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JarInstance extends JpaInstance implements MDTInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstance.class);

	JarInstance(JarInstanceManager manager, JpaInstanceDescriptor desc) {
		super(manager, desc);
		
		setLogger(s_logger);
	}
	
	public File getHomeDir() {
		return ((JarInstanceManager)m_manager).getInstanceHomeDir(getId());
	}

	@Override
	public void startAsync() throws MDTInstanceManagerException {
		JpaInstanceDescriptor desc = asJpaInstanceDescriptor();
		
		JarInstanceManager mgr = getInstanceManager();
		JarExecutionArguments jargs = mgr.parseExecutionArguments(desc.getArguments());

		JarInstanceExecutor exector = getExecutor();
		if ( getLogger().isInfoEnabled() ) {
			getLogger().info("starting: {}, port={}...", this, jargs.getPort());
		}
		exector.start(getId(), getAasId(), jargs);
	}

	@Override
	public void stopAsync() {
		getExecutor().stop(getId());
	}
	
	public JarInstanceManager getInstanceManager() {
		return (JarInstanceManager)m_manager;
	}

	@Override
	public String getOutputLog() throws IOException {
		return getExecutor().getOutputLog(getId());
	}
	
	@Override
	public String toString() {
		return String.format("JarInstance[id=%s, path=%s]", getId(), getHomeDir());
	}

	@Override
	protected void uninitialize() {
		// Instance용 디렉토리를 제거한다.
		File instanceDir = getHomeDir();
		
		try {
			FileUtils.deleteDirectory(instanceDir);
		}
		catch ( IOException e ) {
			getLogger().warn("Failed to delete MDTInstance workspace: dir={}, cause={}", instanceDir, e);
			throw new MDTInstanceManagerException("Failed to delete instance directory: dir=" + instanceDir);
		}
	}
	
	private JarInstanceExecutor getExecutor() {
		return getInstanceManager().getInstanceExecutor();
	}
}