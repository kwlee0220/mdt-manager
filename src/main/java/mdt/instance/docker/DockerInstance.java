package mdt.instance.docker;

import java.util.List;

import org.mandas.docker.client.DockerClient;
import org.mandas.docker.client.DockerClient.RemoveContainerParam;
import org.mandas.docker.client.exceptions.ContainerNotFoundException;
import org.mandas.docker.client.exceptions.DockerException;
import org.mandas.docker.client.messages.Container;
import org.mandas.docker.client.messages.ContainerInfo;
import org.mandas.docker.client.messages.PortBinding;

import utils.InternalException;

import mdt.Globals;
import mdt.instance.AbstractInstance;
import mdt.instance.InstanceStatusChangeEvent;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class DockerInstance extends AbstractInstance implements MDTInstance {
	private static final int SECONDS_TO_WAIT_BEFORE_KILLING = 30;
	
	private final Container m_container;
	
	DockerInstance(DockerInstanceManager manager, JpaInstanceDescriptor desc, Container container) {
		super(manager, desc);
		
		m_container = container;
	}

	@Override
	protected void uninitialize() {
		// 필요한 작업이 뭔지 몰라서 일단 빈 상태로 둠.
		// 나중에 확인할 필요가 있음.
	}
	
	@Override
	public MDTInstanceStatus getStatus() {
		try ( DockerClient docker = newDockerClient() ) {
			m_container.state();
			ContainerInfo info = docker.inspectContainer(m_container.id());
			if ( info.state().running() ) {
				return MDTInstanceStatus.RUNNING;
			}
			else if ( info.state().error().length() > 0 ) {
				return MDTInstanceStatus.FAILED;
			}
			else {
				return MDTInstanceStatus.STOPPED;
			}
		}
		catch ( ContainerNotFoundException e ) {
			return MDTInstanceStatus.STOPPED;
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}

	@Override
	public String getBaseEndpoint() {
		try ( DockerClient docker = newDockerClient() ) {
			ContainerInfo info = docker.inspectContainer(m_container.id());
			if ( info.state().running() ) {
				int repoPort = getRepositoryPort(info);
				return getInstanceManager().toServiceEndpoint(repoPort);
			}
			else {
				return null;
			}
		}
		catch ( ContainerNotFoundException e ) {
			return null;
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}

	@Override
	public void startAsync() {
		try ( DockerClient docker = newDockerClient() ) {
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STARTING(getId()));
			docker.startContainer(m_container.id());
			
			ContainerInfo info = docker.inspectContainer(m_container.id());
			int repoPort = getRepositoryPort(info);
			
			String svcEndpoint = getInstanceManager().toServiceEndpoint(repoPort);
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.RUNNING(getId(), svcEndpoint));
		}
		catch ( Exception e ) {
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPED(getId()));
			if ( e instanceof ContainerNotFoundException ) {
				throw new ResourceNotFoundException("DockerInstance", "container-id=" + m_container.id());
			}
			
			throw new MDTInstanceManagerException("Failed to start MDTInstance: id=" + getId()
													+ ", cause=" + e);
		}
	}
	
	@Override
	public void stopAsync() {
		try ( DockerClient docker = newDockerClient() ) {
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPING(getId()));
			
			docker.stopContainer(m_container.id(), SECONDS_TO_WAIT_BEFORE_KILLING);
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPED(getId()));
		}
		catch ( ContainerNotFoundException e ) {
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPED(getId()));
		}
		catch ( InterruptedException | DockerException e ) {
			throw new MDTInstanceManagerException("Failed to stop the MDTInstance: id=" + getId()
													+ ", cause=" + e);
		}
	}
	
	protected void remove() {
		try ( DockerClient docker = newDockerClient() ) {
			docker.removeContainer(m_container.id(), RemoveContainerParam.forceKill());
		}
		catch ( ContainerNotFoundException expected ) { }
		catch ( Exception e ) {
			throw new MDTInstanceManagerException("" + e);
		}
	}
	
	private DockerClient newDockerClient() {
		DockerInstanceManager mgr = getInstanceManager();
		return mgr.newDockerClient();
	}
	
	private int getRepositoryPort(ContainerInfo info) {
		List<PortBinding> hostPorts = info.networkSettings().ports().get("443/tcp");
		if ( hostPorts == null || hostPorts.size() == 0 ) {
			throw new InternalException("Cannot find external port");
		}
		return Integer.parseInt(hostPorts.get(0).hostPort());
	}
}
