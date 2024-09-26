package mdt.instance.k8s;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import utils.stream.FStream;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class KubernetesRemote implements Closeable {
	private final KubernetesClient m_client;
	
	public static KubernetesRemote connect() {
		KubernetesClient client = new KubernetesClientBuilder().build();
		return new KubernetesRemote(client);
	}
	
	private KubernetesRemote(KubernetesClient client) {
		m_client = client;
	}

	@Override
	public void close() throws IOException {
		m_client.close();
	}
	
	public List<Pod> listPods() {
		List<Pod> plist = m_client.pods().inNamespace("default").list().getItems();
		for ( Pod pod: plist ) {
			System.out.println(pod.getMetadata().getName());
		}
		
		return plist;
	}
	
	public Namespace createNamespace(String name) {
		Namespace ns = new NamespaceBuilder()
							.withNewMetadata()
								.withName(name)
							.endMetadata()
						.build();
		return m_client.namespaces().resource(ns).create();
	}
	
	public Namespace getNamespace(String name) {
		return m_client.namespaces().withName(name).get();
	}
	
	public List<Node> getNodeAll() {
		return m_client.nodes().list().getItems();
	}
	
	public Node getControlPlane() {
		return FStream.from(m_client.nodes().list().getItems())
						.findFirst(KubernetesRemote::isControlPlane)
						.get();
	}
	
	public List<Node> getWorkerNodeAll() {
//		for ( Node node: m_client.nodes().list().getItems() ) {
//			System.out.println(node.getSpec().getTaints() + ", " + KubernetesRemote.isControlPlane(node));
//		}
		return FStream.from(m_client.nodes().list().getItems())
						.filterNot(KubernetesRemote::isControlPlane)
						.toList();
	}
	
	public Pod createPod(String ns, Pod resource) {
		return m_client.pods().inNamespace(ns).resource(resource).create();
	}
	
	public Pod getPod(String ns, String name) {
		List<Pod> podList = m_client.pods().inNamespace(ns).list().getItems();
		return FStream.from(podList)
						.filter(pod -> pod.getMetadata().getName().startsWith(name))
						.findFirst()
						.getOrNull();
	}
	
	public int createService(String ns, Service svc) {
		Service service = m_client.services()
								.inNamespace(ns)
								.resource(svc)
								.create();
		return service.getSpec().getPorts().get(0).getNodePort();
	}
	
	public Service getService(String ns, String svcName) {
		return m_client.services()
						.inNamespace(ns)
						.withName(svcName)
						.get();
	}
	
	public void deleteService(String ns, String name) {
		Service svc = getService(ns, name);
		if ( svc != null ) {
			deleteService(svc);
		}
	}
	
	public void deleteService(Service svc) {
		m_client.resource(svc).delete();
	}
	
	public Deployment getDeployment(String ns, String deploymentName) {
		return m_client.apps()
						.deployments()
						.inNamespace(ns)
						.withName(deploymentName)
						.get();
	}
	
	public Deployment createDeployment(String ns, Deployment deployment) {
		return m_client.apps()
						.deployments()
						.inNamespace(ns)
						.resource(deployment)
						.create();
	}
	
	public void deleteDeployment(String ns, String deploymentName) {
		Deployment deployment = getDeployment(ns, deploymentName);
		if ( deployment != null ) {
			deleteDeployment(deployment);
		}
	}
	
	public void deleteDeployment(Deployment dep) {
		m_client.resource(dep).delete();
	}
	
	private static boolean isControlPlane(Node node) {
		return FStream.from(node.getSpec().getTaints())
						.exists(taint -> taint.getKey().contains("control-plane"));
	}
}
