package mdt.instance;

import java.io.File;
import java.io.IOException;

import mdt.model.ModelValidationException;
import mdt.model.ServiceFactory;
import mdt.model.instance.MDTInstance;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.instance.MDTInstanceManagerException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface MDTInstanceManagerProvider extends MDTInstanceManager {
	public ServiceFactory getServiceFactory();
	
	/**
	 * 새로운 MDTInstance를 등록한다.
	 * 
	 * @param id		새로 등록할 MDTInstance 식별자.
//	 * @param port	FA3ST 서비스 포트.
	 * @param bundleDir		등록할 MDTInstance 정보가 저장된  디렉토리 경로
	 * @return	등록된 MDTInstance 등록정보 객체.
	 * @throws ModelValidationException	등록 정보의 유효성 검사 실패.
	 * @throws IOException					입출력 오류.
	 * @throws MDTInstanceManagerException	기타 이유로 MDTInstance 등록에 실패한 경우.
	 */
	public MDTInstance addInstance(String id, int port, File bundleDir)
		throws ModelValidationException, IOException, MDTInstanceManagerException;
}
