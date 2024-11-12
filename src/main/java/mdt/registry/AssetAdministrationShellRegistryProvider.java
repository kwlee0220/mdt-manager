package mdt.registry;

import mdt.aas.AssetAdministrationShellRegistry;
import mdt.model.ResourceNotFoundException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface AssetAdministrationShellRegistryProvider extends AssetAdministrationShellRegistry {
	public String getJsonAssetAdministrationShellDescriptorById(String aasId)
		throws ResourceNotFoundException;
}
