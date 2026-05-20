package mdt.instance;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import utils.stream.FStream;

import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.model.AASUtils;
import mdt.model.instance.MDTOperationDescriptor;
import mdt.model.instance.MDTParameterDescriptor;
import mdt.model.instance.MDTParameterService;
import mdt.model.instance.MDTParameterServiceCollection;
import mdt.model.instance.MDTSubmodelDescriptor;
import mdt.model.instance.MDTTwinCompositionDescriptor;


/**
 * JPA 저장소에 보관된 메타정보를 통해 AAS/Submodel/Parameter/Operation 등 각종 디스크립터를
 * 매니저에게 위임 조회하는 {@link AbstractInstance} 기반 추상 구현이다.
 * <p>
 * 본 클래스는 데이터 접근만 담당하고, 인스턴스 실행 환경에 따라 달라지는 생명주기 동작
 * ({@link #startAsync()}, {@link #stopAsync()}, {@link #uninitialize()})은 구체 서브클래스에서
 * 구현되어야 한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public abstract class JpaInstance extends AbstractInstance {
	private static final Logger s_logger = LoggerFactory.getLogger(JpaInstance.class);

	/**
	 * 주어진 매니저와 JPA {@link JpaInstanceDescriptor}로 {@link JpaInstance}를 생성한다.
	 *
	 * @param manager	본 인스턴스가 속한 {@link AbstractJpaInstanceManager}.
	 * @param desc		JPA 저장소에서 조회한 {@link JpaInstanceDescriptor}.
	 */
	protected JpaInstance(AbstractJpaInstanceManager<? extends JpaInstance> manager, JpaInstanceDescriptor desc) {
		super(manager, desc.toInstanceDescriptor());

		setLogger(s_logger);
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * 매니저로부터 {@link AssetAdministrationShellDescriptor}를 조회하여,
	 * 인스턴스의 서비스 endpoint가 활성화되어 있으면 해당 endpoint 정보를 디스크립터에 부착하여 반환한다.
	 */
	@Override
	public AssetAdministrationShellDescriptor getAASShellDescriptor() {
		AssetAdministrationShellDescriptor shell = m_manager.getAssetAdministrationShellDescriptor(getId());
		if ( getServiceEndpoint() != null ) {
			shell = AASUtils.attachEndpoint(shell, getServiceEndpoint());
		}
		return shell;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * 매니저로부터 모든 {@link SubmodelDescriptor}를 조회하여,
	 * 인스턴스의 서비스 endpoint가 활성화되어 있으면 각 디스크립터에 해당 endpoint 정보를 부착해 반환한다.
	 */
	@Override
	public List<SubmodelDescriptor> getAASSubmodelDescriptorAll() {
		List<SubmodelDescriptor> descList = m_manager.getAASSubmodelDescriptorAll(getId());
		if ( getServiceEndpoint() != null ) {
			descList = FStream.from(descList)
								.map( smDesc -> AASUtils.attachEndpoint(smDesc, getServiceEndpoint()))
								.toList();
		}

		return descList;
	}

	/**
	 * 본 MDTInstance의 실행 인자 문자열을 반환한다.
	 *
	 * @return JPA {@link JpaInstanceDescriptor}에 보관된 실행 인자 문자열.
	 */
	public String getExecutionArguments() {
		return getJpaInstanceDescriptor().getArguments();
	}

	@Override
	public List<MDTSubmodelDescriptor> getMDTSubmodelDescriptorAll() {
		return m_manager.getMDTSubmodelDescriptorAll(getId());
	}

	/**
	 * 본 MDTInstance에 등록된 모든 {@link MDTParameterDescriptor}를 반환한다.
	 *
	 * @return {@link MDTParameterDescriptor} 객체 리스트.
	 */
	public List<MDTParameterDescriptor> getMDTParameterDescriptorAll() {
		return m_manager.getMDTParameterDescriptorAll(getId());
	}

	@Override
	public List<MDTParameterService> getParameterServiceAll() {
		return new MDTParameterServiceCollection(this, getMDTParameterDescriptorAll());
	}

	@Override
	public List<MDTOperationDescriptor> getMDTOperationDescriptorAll() {
		return m_manager.getMDTOperationDescriptorAll(getId());
	}

	@Override
	public MDTTwinCompositionDescriptor getMDTTwinCompositionDescriptor() {
		return m_manager.getTwinCompositionDescriptor(getId());
	}

	/**
	 * 본 MDTInstance에 해당하는 JPA 디스크립터 ({@link JpaInstanceDescriptor})를 매니저에서 직접 조회한다.
	 * <p>
	 * 상위 클래스 ({@link AbstractInstance#getInstanceDescriptor()})가 보관/캐싱하는
	 * {@link mdt.model.instance.InstanceDescriptor}와 달리, 본 메서드는 호출 시마다
	 * JPA 저장소에서 최신 엔티티를 조회한다. 호출 비용이 크므로 필요한 경우에만 사용한다.
	 *
	 * @return {@link JpaInstanceDescriptor} 객체.
	 */
	protected JpaInstanceDescriptor getJpaInstanceDescriptor() {
		return m_manager.getJpaInstanceDescriptor(getId());
	}
}
