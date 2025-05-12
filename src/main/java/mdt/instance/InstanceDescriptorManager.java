package mdt.instance;

import java.util.List;

import mdt.instance.jpa.JpaInstanceDescriptor;
import mdt.instance.jpa.JpaInstanceDescriptorManager.InstanceDescriptorTransform;
import mdt.instance.jpa.JpaInstanceDescriptorManager.SearchCondition;
import mdt.model.ResourceAlreadyExistsException;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceManagerException;


/**
 * InstanceDescriptor를 관리하는 매니저 인터페이스를 정의한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface InstanceDescriptorManager {
	/**
	 * 식별자에 해당하는 InstanceDescriptor를 검색한다.
	 * 
	 * @param id 검색할 식별자
	 * @return 검색된 InstanceDescriptor
	 * @throws MDTInstanceManagerException 검색 중 오류가 발생한 경우
	 */
	public JpaInstanceDescriptor getInstanceDescriptor(String id) throws MDTInstanceManagerException;
	
	/**
	 * 모든 InstanceDescriptor를 검색한다.
	 * 
	 * @return 모든 InstanceDescriptor
	 */
	public List<JpaInstanceDescriptor> getInstanceDescriptorAll() throws MDTInstanceManagerException;
	
	/**
	 * 주어진 필터 조건에 해당하는 InstanceDescriptor를 검색한다.
	 *
	 * @param filterExpr	필터 조건
	 * @return	검색된 InstanceDescriptor 리스트.
	 * @throws MDTInstanceManagerException	검색 중 오류가 발생한 경우
	 */
	public List<JpaInstanceDescriptor> findInstanceDescriptorAll(String filterExpr) throws MDTInstanceManagerException;

	/**
	 * 주어진 InstanceDescriptor를 추가한다.
	 * 
	 * @param desc 추가할 InstanceDescriptor
	 * @throws ResourceAlreadyExistsException 이미 같은 식별자의 InstanceDescriptor가 존재하는 경우
	 * @throws MDTInstanceManagerException 추가 중 오류가 발생한 경우
	 */
	public void addInstanceDescriptor(InstanceDescriptor desc) throws MDTInstanceManagerException,
																		ResourceAlreadyExistsException;
	
	/**
	 * 주어진 식별자에 해당하는 InstanceDescriptor를 제거한다.
	 * <p>
	 * 만약 해당 식별자에 해당하는 InstanceDescriptor가 존재하지 않는 경우는 무시한다.
	 *
	 * @param id	제거할 InstanceDescriptor의 식별자
	 * @throws MDTInstanceManagerException	제거 중 오류가 발생한 경우
	 */
	public void removeInstanceDescriptor(String id) throws MDTInstanceManagerException;
	
	public long count();

	public <S> List<S> query(SearchCondition cond, InstanceDescriptorTransform<S> transform);
}
