package mdt.repository;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface SubmodelRepositoryProvider extends AutoCloseable {
	public List<ServiceIdentifier> getAllSubmodels();
	public ServiceIdentifier getSubmodelById(String id);
	public List<ServiceIdentifier> getAllSubmodelBySemanticId(String semanticId);
	public List<ServiceIdentifier> getAllSubmodelsByIdShort(String idShort);
	
	public ServiceIdentifier addSubmodel(Submodel submodel);
	public ServiceIdentifier updateSubmodelById(Submodel submodel);
	public void removeSubmodelById(String id);
}
