package org.joget.marketplace;

import io.blockfrost.sdk.api.IPFSService;
import io.blockfrost.sdk.api.model.ipfs.IPFSObject;
import io.blockfrost.sdk.api.util.Constants;
import io.blockfrost.sdk.api.util.NetworkHelper;
import io.blockfrost.sdk.impl.IPFSServiceImpl;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.lib.FileUpload;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.Form;
import org.joget.apps.form.model.FormBuilderPalette;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.apps.form.model.FormStoreBinder;
import org.joget.apps.form.service.FileUtil;
import org.joget.apps.form.service.FormUtil;
import org.joget.commons.util.FileManager;
import org.joget.commons.util.FileStore;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.ResourceBundleUtil;
import org.joget.commons.util.SecurityUtil;
import org.joget.workflow.util.WorkflowUtil;
import org.json.JSONObject;
import org.springframework.web.multipart.MultipartFile;

public class IpfsFileUpload extends FileUpload {
    
    /*
        NOTE:
        - Multi file upload feature removed due to illogical relevance to IPFS.
        - For any form DELETE action, you MUST enable "Delete Associated Child Form Data?" property for unpinning to work correctly.
    */
    
    @Override
    public String getName() {
        return "IPFS File Upload";
    }

    @Override
    public String getVersion() {
        return "7.0.0";
    }

    @Override
    public String getDescription() {
        return "Similar to the default File Upload form element, with the additional ability to write files to IPFS.";
    }
    
    @Override
    public FormRowSet formatData(FormData formData) { //Modified for single file upload only
        FormRowSet rowSet = null;
        
        String id = getPropertyString(FormUtil.PROPERTY_ID);
        String ipfsCidField = getPropertyString("ipfsCidField");
        
        Form form = FormUtil.findRootForm(this);
        Element ipfsCidElement = FormUtil.findElement(ipfsCidField, form, formData);
        
        final String cidParamName = FormUtil.getElementParameterName(ipfsCidElement);
        
        final String originalValue = formData.getLoadBinderDataProperty(form, id);
        final String originalIpfsCid = formData.getLoadBinderDataProperty(form, ipfsCidField);
        final String blockfrostIpfsProjectKey = getPropertyString("blockfrostIpfsProjectKey");
        
        Set<String> remove = null;
        
        if ("true".equals(getPropertyString("removeFile")) && originalValue != null) {
            remove = new HashSet<String>();
            remove.addAll(Arrays.asList(originalValue.split(";")));
        }

        // get value
        if (id != null) {
            String[] values = FormUtil.getElementPropertyValues(this, formData);
            if (values != null && values.length > 0) {
                // set value into Properties and FormRowSet object
                
                List<String> resultedValue = new ArrayList<String>();
                List<String> filePaths = new ArrayList<String>();
                
                String value = values[0];
                
                // check if the file is in temp file
                File file = FileManager.getFileByPath(value);
                if (file != null) {
                    filePaths.add(value);
                    resultedValue.add(file.getName());
                    
                    File originalFile = null;
                    boolean fileMatch = false;
                    
                    // Check if stored & uploaded files are exactly the same
                    if (originalValue != null && !originalValue.isBlank()) {
                        try {
                            originalFile = FileUtil.getFile(originalValue, this, formData.getPrimaryKeyValue());
                        } catch (Exception ex) {
                            LogUtil.error(getClass().getName(), ex, "Unable to find original file in storage");
                            return null;
                        }
                        try {
                            fileMatch = getFileHash(originalFile).equals(getFileHash(file));
                        } catch (Exception ex) {
                            LogUtil.error(getClass().getName(), ex, "Unable to compare original & uploaded files");
                            return null;
                        }
                    }
                    
                    // Handle writing file to IPFS
                    if (originalValue == null || originalValue.isBlank() || !fileMatch) {
                        IPFSService ipfsService = new IPFSServiceImpl(Constants.BLOCKFROST_IPFS_URL, blockfrostIpfsProjectKey);
                        try {
                            if (originalValue != null && !originalValue.isBlank()) {
                                ipfsService.removePinnedObject(originalIpfsCid);
                                LogUtil.info(getClass().getName(), "File replacement detected. Unpinning previous cid of: " + originalIpfsCid);
                            }
                            
                            IPFSObject ipfsObject = ipfsService.add(file);
                            String ipfsCid = ipfsObject.getIpfsHash();
                            ipfsService.pinAdd(ipfsCid);
                            LogUtil.info(getClass().getName(), "New file is pinned with cid of: " + ipfsCid);
                            formData.addRequestParameterValues(cidParamName, new String[]{ipfsCid});
                        } catch (Exception ex) {
                            LogUtil.error(getClass().getName(), ex, "Unable to upload file to IPFS");
                            return null;
                        } finally {
                            NetworkHelper.getInstance().shutdown();
                        }
                    }
                } else {
                    if (remove != null && !value.isEmpty()) {
                        remove.remove(value);
                    }
                    resultedValue.add(value);
                    
                    //Handle IPFS CID unpinning if file is deleted. Ignore if untouched.
                    if (value.isBlank()) {
                        formData.addRequestParameterValues(cidParamName, new String[]{""});
                        if (originalIpfsCid != null && !originalIpfsCid.isBlank()) {
                            IPFSService ipfsService = new IPFSServiceImpl(Constants.BLOCKFROST_IPFS_URL, blockfrostIpfsProjectKey);
                            try {
                                ipfsService.removePinnedObject(originalIpfsCid);
                                LogUtil.info(getClass().getName(), "File deleted. Unpinning file cid of: " + originalIpfsCid);
                            } catch (Exception ex) {
                                LogUtil.error(getClass().getName(), ex, "Unable to unpin file on IPFS");
                                return null;
                            } finally {
                                NetworkHelper.getInstance().shutdown();
                            }
                        }
                    }
                }
                
                FormRow result = new FormRow();
                
                if (!filePaths.isEmpty()) {
                    result.putTempFilePath(id, filePaths.toArray(new String[]{}));
                }
                
                if (remove != null) {
                    result.putDeleteFilePath(id, remove.toArray(new String[]{}));
                }
                
                // formulate values
                String delimitedValue = FormUtil.generateElementPropertyValues(resultedValue.toArray(new String[]{}));
                String paramName = FormUtil.getElementParameterName(this);
                formData.addRequestParameterValues(paramName, resultedValue.toArray(new String[]{}));
                        
                // set value into Properties and FormRowSet object
                result.setProperty(id, delimitedValue);
                rowSet = new FormRowSet();
                rowSet.add(result);
                
                //Workaround due to setting custom store binder wrapper
                formData.setStoreBinderData(form.getStoreBinder(), rowSet);
                
                String filePathPostfix = "_path";
                formData.addRequestParameterValues(id + filePathPostfix, new String[]{});
            }
        }
        
        return rowSet;
    }
    
    @Override
    public void setStoreBinder(FormStoreBinder storeBinder) {
        /* 
            When custom store binder is set, formData will keep storeBinderData separate from form object's storeBinderData.
            Must manually handle data to be stored by this element. See formatData() above.
        */
        
        IpfsFileUploadStoreBinderWrapper binder = new IpfsFileUploadStoreBinderWrapper(getProperties());
        super.setStoreBinder(binder);
    }
    
    public static String getFileHash(File file) throws FileNotFoundException, IOException {
        if (file == null) {
            return null;
        }
//        return DigestUtils.md5Hex(new FileInputStream(file));
        return DigestUtils.sha256Hex(new FileInputStream(file)); //Relatively modern CPUs have negligible performance impact
    }
    
    @Override
    public String getServiceUrl() {     
        //Changed to match this class name
        String url = WorkflowUtil.getHttpServletRequest().getContextPath()+ "/web/json/plugin/" + getClass().getName() + "/service";
        AppDefinition appDef = AppUtil.getCurrentAppDefinition();
        
        //create nonce
        String paramName = FormUtil.getElementParameterName(this);
        String fileType = getPropertyString("fileType");
        String nonce = SecurityUtil.generateNonce(
                new String[]{
                    getClass().getSimpleName(), //Changed to match this class name
                    appDef.getAppId(), 
                    appDef.getVersion().toString(), 
                    paramName, 
                    fileType
                }, 1
        );
        
        try {
            url = url + 
                    "?_nonce=" + URLEncoder.encode(nonce, "UTF-8") +
                    "&_paramName=" + URLEncoder.encode(paramName, "UTF-8") +
                    "&_appId=" + URLEncoder.encode(appDef.getAppId(), "UTF-8") +
                    "&_appVersion=" + URLEncoder.encode(appDef.getVersion().toString(), "UTF-8") +
                    "&_ft=" + URLEncoder.encode(fileType, "UTF-8");
        } catch (Exception e) {}
        
        return url;
    }
    
    @Override
    public void webService(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String nonce = request.getParameter("_nonce");
        String paramName = request.getParameter("_paramName");
        String appId = request.getParameter("_appId");
        String appVersion = request.getParameter("_appVersion");
        String filePath = request.getParameter("_path");
        String fileType = request.getParameter("_ft");

        //Changed to match this class name
        if (!SecurityUtil.verifyNonce(nonce, new String[]{getClass().getSimpleName(), appId, appVersion, paramName, fileType})) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, ResourceBundleUtil.getMessage("general.error.error403"));
        }
        
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            try {
                JSONObject obj = new JSONObject();
                try {
                    // handle multipart files
                    String validatedParamName = SecurityUtil.validateStringInput(paramName);
                    MultipartFile file = FileStore.getFile(validatedParamName);
                    if (file != null && file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty()) {
                        String ext = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".")).toLowerCase();
                        if (fileType != null && (fileType.isEmpty() || fileType.contains(ext+";") || fileType.endsWith(ext))) {
                            String path = FileManager.storeFile(file);
                            obj.put("path", path);
                            obj.put("filename", file.getOriginalFilename());
                            obj.put("newFilename", path.substring(path.lastIndexOf(File.separator) + 1));
                        } else {
                            obj.put("error", ResourceBundleUtil.getMessage("form.fileupload.fileType.msg.invalidFileType"));
                        }
                    }

                    Collection<String> errorList = FileStore.getFileErrorList();
                    if (errorList != null && !errorList.isEmpty() && errorList.contains(paramName)) {
                        obj.put("error", ResourceBundleUtil.getMessage("general.error.fileSizeTooLarge", new Object[]{FileStore.getFileSizeLimit()}));
                    }
                } catch (Exception e) {
                    obj.put("error", e.getLocalizedMessage());
                } finally {
                    FileStore.clear();
                }
                obj.write(response.getWriter());
            } catch (Exception ex) {}
        } else if (filePath != null && !filePath.isEmpty()) {
            String normalizedFilePath = SecurityUtil.normalizedFileName(filePath);

            File file = FileManager.getFileByPath(normalizedFilePath);
            
            if (file == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            
            ServletOutputStream stream = response.getOutputStream();
            DataInputStream in = new DataInputStream(new FileInputStream(file));
            byte[] bbuf = new byte[65536];

            try {
                String contentType = request.getSession().getServletContext().getMimeType(file.getName());
                if (contentType != null) {
                    response.setContentType(contentType);
                }

                // send output
                int length = 0;
                while ((in != null) && ((length = in.read(bbuf)) != -1)) {
                    stream.write(bbuf, 0, length);
                }
            } catch (Exception e) {
                //do nothing
            } finally {
                in.close();
                stream.flush();
                stream.close();
            }
        }
    }
    
    @Override
    public String getLabel() {
        return getName();
    }
    
    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(), "/properties/ipfsFileUpload.json", null, true, "messages/ipfsFileUpload");
    }

    @Override
    public String getFormBuilderCategory() {
        return FormBuilderPalette.CATEGORY_CUSTOM;
    }

    @Override
    public int getFormBuilderPosition() {
        return 1;
    }

    @Override
    public String getFormBuilderIcon() {
        return "<i class=\"fas fa-cloud-upload-alt\"></i>";
    }

    @Override
    public String getFormBuilderTemplate() {
        return "<label class='label'>IPFS File Upload</label><input type='file' />";
    }
}
