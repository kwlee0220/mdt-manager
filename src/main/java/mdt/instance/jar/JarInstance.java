package mdt.instance.jar;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mdt.exector.jar.JarInstanceExecutor;
import mdt.instance.AbstractInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.JarExecutionArguments;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.service.SubmodelService;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class JarInstance extends AbstractInstance implements MDTInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstance.class);

	JarInstance(JarInstanceManager manager, JpaInstanceDescriptor desc) {
		super(manager, desc);
	}

	@Override
	public void startAsync() throws MDTInstanceManagerException {
		JpaInstanceDescriptor desc = getInstanceDescriptor();
		
		JarInstanceManager mgr = getInstanceManager();
		JarExecutionArguments jargs = mgr.parseExecutionArguments(desc.getArguments());

		JarInstanceExecutor exector = getExecutor();
		if ( s_logger.isDebugEnabled() ) {
			s_logger.debug("starting...: " + this);
		}
		exector.start(getId(), getAasId(), jargs);
	}

	@Override
	public void stopAsync() {
		getExecutor().stop(getId());
	}
	
	@Override
	public String toString() {
		return String.format("JarInstance[id=%s, aas_id=%s, path=%s]", getId(), getAasId(), getWorkspaceDir());
	}

	@Override
	protected void uninitialize() {
		// Instance용 디렉토리를 제거한다.
		File instanceDir = getInstanceWorkspaceDir();
		
		try {
			FileUtils.deleteDirectory(instanceDir);
		}
		catch ( IOException e ) {
			s_logger.warn("Failed to delete MDTInstance workspace: dir={}, cause={}", instanceDir, e);
			throw new MDTInstanceManagerException("Failed to delete instance directory: dir=" + instanceDir);
		}
	}
	
	private JarInstanceExecutor getExecutor() {
		JarInstanceManager mgr = getInstanceManager();
		return mgr.getInstanceExecutor();
	}
}