package mdt.repository;

import java.util.List;

import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface AssetAdministrationShellRepositoryProvider extends AutoCloseable {
	public List<ServiceIdentifier> getAllAssetAdministrationShells();
	public ServiceIdentifier getAssetAdministrationShellById(String aasId);
	public List<ServiceIdentifier> getAllAssetAdministrationShellsByAssetId(String key);
	public List<ServiceIdentifier> getAssetAdministrationShellByIdShort(String idShort);
	
	public ServiceIdentifier addAssetAdministrationShell(AssetAdministrationShell aas);
	public ServiceIdentifier updateAssetAdministrationShellById(AssetAdministrationShell aas);
	public void removeAssetAdministrationShellById(String id);
}
