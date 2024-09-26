package mdt.registry;

import mdt.model.ResourceNotFoundException;
import mdt.model.registry.AssetAdministrationShellRegistry;
import mdt.model.registry.RegistryException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface AssetAdministrationShellRegistryProvider extends AssetAdministrationShellRegistry {
	public String getJsonAssetAdministrationShellDescriptorById(String aasId)
		throws ResourceNotFoundException, RegistryException;
}
