package mdt.instance;

import java.io.File;
import java.io.IOException;

import mdt.model.ModelValidationException;
import mdt.model.ServiceFactory;
import mdt.model.instance.InstanceDescriptor;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceStatus;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface MDTInstanceManagerProvider extends MDTInstanceManager {
	public ServiceFactory getServiceFactory();
	
	/**
	 * 식별자에 해당하는 MDTInstance의 상태를 반환한다.
	 * 
	 * @param id	검색 대상 MDTInstance 식별자.
	 * @return	MDTInstance의 상태.
	 */
	public MDTInstanceStatus getInstanceStatus(String id);
	
	/**
	 * 식별자에 해당하는 MDTInstance의 서비스 endpoint를 반환한다.
	 * 
	 * @param id 검색 대상 MDTInstance 식별자.
	 * @return MDTInstance의 서비스 endpoint.
	 */
	public String getInstanceServiceEndpoint(String id);
	
	/**
	 * 새로운 MDTInstance를 등록한다.
	 * 
	 * @param id		새로 등록할 MDTInstance 식별자.
	 * @param faaastPort	FA3ST 서비스 포트.
	 * @param bundleDir		등록할 MDTInstance 정보가 저장된  디렉토리 경로
	 * @return	등록된 MDTInstance 등록정보 객체.
	 * @throws ModelValidationException	등록 정보의 유효성 검사 실패.
	 * @throws IOException	파일 입출력 오류.
	 */
	public InstanceDescriptor addInstance(String id, int faaastPort, File bundleDir)
		throws ModelValidationException, IOException;
}
