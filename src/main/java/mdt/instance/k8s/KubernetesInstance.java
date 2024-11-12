package mdt.instance.k8s;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.Throwables;
import utils.func.Lazy;
import utils.func.Unchecked;
import utils.stream.FStream;

import mdt.Globals;
import mdt.instance.JpaInstance;
import mdt.instance.jar.JarInstance;
import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.instance.InstanceStatusChangeEvent;
import mdt.model.instance.MDTInstanceManagerException;
import mdt.model.instance.MDTInstanceStatus;
import mdt.model.service.MDTInstance;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class KubernetesInstance extends JpaInstance implements MDTInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JarInstance.class);
	public static final String NAMESPACE = "mdt-instance";
	
	private final Lazy<KubernetesRemote> m_kube = Lazy.of(this::newKubernetesRemote);
	private String m_workerHostname = null;
	
	KubernetesInstance(KubernetesInstanceManager manager, JpaInstanceDescriptor desc) {
		super(manager, desc);
		
		setLogger(s_logger);
	}
	
	private KubernetesRemote newKubernetesRemote() {
		return ((KubernetesInstanceManager)m_manager).newKubernetesRemote();
	}

	@Override
	protected void uninitialize() throws IOException {
		m_kube.ifLoadedOrThrow(KubernetesRemote::close);
	}

	public String loadEndpoint() {
		Service service = m_kube.get().getService(NAMESPACE, getId());
		if ( service != null ) {
			int port = service.getSpec().getPorts().get(0).getNodePort();
			return toServiceEndpoint(port);
		}
		else {
			return null;
		}
	}

	public MDTInstanceStatus loadStatus() {
		Pod pod = m_kube.get().getPod(NAMESPACE, getId());
		if ( pod == null ) {
			return MDTInstanceStatus.STOPPED;
		}
		
		String phase = pod.getStatus().getPhase();
		switch ( phase ) {
			case "Pending":
				return MDTInstanceStatus.STARTING;
			case "Running":
				return MDTInstanceStatus.RUNNING;
			case "Succeeded":
				return MDTInstanceStatus.STOPPED;
			case "Failed":
				return MDTInstanceStatus.FAILED;
			case "Unknown":
				return MDTInstanceStatus.FAILED;
			default:
				throw new AssertionError();
		}
	}

	@Override
	public void startAsync() {
		JpaInstanceDescriptor desc = asJpaInstanceDescriptor();

		KubernetesRemote k8s = m_kube.get();
		Deployment deployment = null;
		try {
			KubernetesInstanceManager mgr = getInstanceManager();
			KubernetesExecutionArguments args = mgr.parseExecutionArguments(desc.getArguments());
			
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STARTING(desc.getId()));
			
			deployment = buildDeploymentResource(args.getImageRepoName());
			deployment = k8s.createDeployment(NAMESPACE, deployment);
			
			Service svc = buildServiceResource();

			m_workerHostname = selectWorkerHostname();
			int svcPort = k8s.createService(NAMESPACE, svc);
			String endpoint = toServiceEndpoint(svcPort);
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.RUNNING(desc.getId(), endpoint));
		}
		catch ( Exception e ) {
			Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPED(desc.getId()));
			
			Unchecked.acceptOrIgnore(k8s::deleteDeployment, deployment);
			Throwables.throwIfInstanceOf(e, MDTInstanceManagerException.class);
			throw new MDTInstanceManagerException("Failed to start MDTInstance: id=" + getId() + ", cause=" + e);
		}
	}

	@Override
	public void stopAsync() {
		KubernetesRemote k8s = m_kube.get();

		Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPING(getId()));
		
		Unchecked.runOrIgnore(() -> k8s.deleteService(NAMESPACE, toServiceName(getId())));
		Unchecked.runOrIgnore(() -> k8s.deleteDeployment(NAMESPACE, toDeploymentName(getId())));
		m_workerHostname = null;

		Globals.EVENT_BUS.post(InstanceStatusChangeEvent.STOPPED(getId()));
	}
	
	public KubernetesInstanceManager getInstanceManager() {
		return (KubernetesInstanceManager)m_manager;
	}
	
	private String toServiceEndpoint(int svcPort) {
		if ( m_workerHostname == null ) {
			m_workerHostname = selectWorkerHostname();
		}
		return String.format("https://%s:%d/api/v3.0", m_workerHostname, svcPort);
	}
	
	private String selectWorkerHostname() {
		List<Node> workers = m_kube.get().getWorkerNodeAll();
		int idx = new Random().nextInt(workers.size());
		
		List<NodeAddress> addresses = workers.get(idx).getStatus().getAddresses();
		return FStream.from(addresses)
						.findFirst(addr -> addr.getType().equals("Hostname"))
						.getOrElse(addresses.get(addresses.size()-1))
						.getAddress();
	}
	
	private static String toDeploymentName(String instanceId) {
		return instanceId;
	}
	
	private static String toServiceName(String instanceId) {
		return instanceId;
	}
	
	private static String toPodName(String instanceId) {
		return instanceId;
	}
	
	private static String toContainerName(String instanceId) {
		return String.format("container-%s", instanceId);
	}
	
	private Deployment buildDeploymentResource(String imageId) {
        return new DeploymentBuilder()
						.withNewMetadata()
							.withName(toDeploymentName(getId()))
						.endMetadata()
						.withNewSpec()
							.withReplicas(1)
							.withNewSelector()
								.addToMatchLabels("mdt-type", "instance")
								.addToMatchLabels("mdt-instance-id", getId())
							.endSelector()
							.withNewTemplate()
								.withNewMetadata()
									.withName(toPodName(getId()))
									.addToLabels("mdt-type", "instance")
									.addToLabels("mdt-instance-id", getId())
								.endMetadata()
								.withNewSpec()
									.addNewContainer()
										.withName(toContainerName(getId()))
										.withImage(imageId)
										.addNewPort()
											.withContainerPort(443)
										.endPort()
									.endContainer()
								.endSpec()
							.endTemplate()
						.endSpec()
					.build();
	}
	
	private Service buildServiceResource() {
		return new ServiceBuilder()
					.withNewMetadata()
						.withName(toServiceName(getId()))
					.endMetadata()
					.withNewSpec()
						.withType("NodePort")
						.withSelector(Collections.singletonMap("mdt-instance-id", getId()))
						.addNewPort()
							.withName("service-port")
							.withProtocol("TCP")
							.withPort(443)
						.endPort()
				    .endSpec()
			    .build();
	}
}
