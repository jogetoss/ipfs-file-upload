package org.joget.marketplace;

import io.blockfrost.sdk.api.IPFSService;
import io.blockfrost.sdk.api.util.Constants;
import io.blockfrost.sdk.api.util.NetworkHelper;
import io.blockfrost.sdk.impl.IPFSServiceImpl;
import java.util.Map;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.FormBinder;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormDeleteBinder;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.model.FormStoreBinder;
import org.joget.commons.util.LogUtil;

public class IpfsFileUploadStoreBinderWrapper extends FormBinder implements FormStoreBinder, FormDeleteBinder {

    protected final Map properties;
    
    @Override
    public String getName() {
        return "IPFS File Upload Store Binder Wrapper";
    }

    @Override
    public String getVersion() {
        return "7.0.1";
    }

    @Override
    public String getDescription() {
        return "Simple wrapper to track form CRUD operations & handle IPFS content pinning.";
    }

    @Override
    public String getLabel() {
        return getName();
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return "";
    }

    public IpfsFileUploadStoreBinderWrapper (Map properties) {
        this.properties = properties;
    }
    
    @Override
    public FormRowSet store(Element element, FormRowSet rows, FormData formData) {
        //Do nothing and just return as is
        return rows;
    }

    @Override
    public void delete(Element element, FormRowSet rows, FormData formData, boolean deleteGrid, boolean deleteSubform, boolean abortProcess, boolean deleteFiles) {
        String blockfrostIpfsProjectKey = (String) properties.get("blockfrostIpfsProjectKey");
        String ipfsCidField = (String) properties.get("ipfsCidField");
        
        String ipfsCid = rows.get(0).getProperty(ipfsCidField);
        
        if (ipfsCid != null && !ipfsCid.isBlank()) {
            IPFSService ipfsService = new IPFSServiceImpl(Constants.BLOCKFROST_IPFS_URL, blockfrostIpfsProjectKey);
            try {
                ipfsService.removePinnedObject(ipfsCid);
                LogUtil.info(getClass().getName(), "Form record deleted. Unpinning file cid of: " + ipfsCid);
            } catch (Exception ex) {
                LogUtil.error(getClass().getName(), ex, "Unable to unpin file on IPFS upon form record deletion");
            } finally {
                NetworkHelper.getInstance().shutdown();
            }
        }
    }
}
