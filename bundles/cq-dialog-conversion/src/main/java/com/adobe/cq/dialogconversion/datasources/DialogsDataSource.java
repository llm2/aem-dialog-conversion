/*************************************************************************
 *
 * ADOBE CONFIDENTIAL
 * __________________
 *
 *  Copyright 2017 Adobe Systems Incorporated
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Adobe Systems Incorporated and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Adobe Systems Incorporated and its
 * suppliers and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Adobe Systems Incorporated.
 *
 **************************************************************************/
package com.adobe.cq.dialogconversion.datasources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.servlet.ServletException;

import com.adobe.granite.ui.components.ExpressionResolver;
import com.adobe.granite.ui.components.ds.DataSource;
import com.adobe.granite.ui.components.ds.SimpleDataSource;
import com.adobe.granite.ui.components.ds.ValueMapResource;
import com.adobe.cq.dialogconversion.DialogRewriteUtils;
import com.adobe.cq.dialogconversion.DialogType;
import com.day.cq.commons.Externalizer;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.jackrabbit.util.ISO9075;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Returns a list of dialogs found on the given path
 */
@SlingServlet(
        resourceTypes = "cq/dialogconversion/components/dialogs/datasource",
        methods = { "GET" })
public final class DialogsDataSource extends SlingSafeMethodsServlet {

    private final static Logger log = LoggerFactory.getLogger(DialogsDataSource.class);

    private static final String NT_DIALOG = "cq:Dialog";
    private static final String NN_CQ_DIALOG = "cq:dialog";
    private static final String NN_DIALOG = "dialog";
    private static final String DIALOG_CONTENT_RESOURCETYPE_PREFIX_CORAL3 = "granite/ui/components/coral/foundation";
    private static final String CRX_LITE_PATH = "/crx/de/index";

    @Reference
    private ExpressionResolver expressionResolver;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
        try {
            Resource resource = request.getResource();
            ResourceResolver resolver = request.getResourceResolver();
            ValueMap properties = resource.getValueMap();
            String itemResourceType = properties.get("itemResourceType", String.class);

            String path = properties.get("path", String.class);

            if (StringUtils.isEmpty(path)) {
                log.warn("Path unavailable");
                return;
            }

            path = expressionResolver.resolve(path, request.getLocale(), String.class, request);

            setDataSource(resource, path, resolver, request, itemResourceType);
        } catch (RepositoryException e) {
            log.warn("Unable to list classic dialogs", e.getMessage());
        }
    }

    private void setDataSource(Resource resource, String path, ResourceResolver resourceResolver, SlingHttpServletRequest request, String itemResourceType) throws RepositoryException {
        List<Resource> resources = new ArrayList<Resource>();

        if (StringUtils.isNotEmpty(path)) {
            Externalizer externalizer = resourceResolver.adaptTo(Externalizer.class);
            Session session = request.getResourceResolver().adaptTo(Session.class);
            TreeMap<String, Node> nodeMap = new TreeMap<String, Node>();

            // sanitize path
            path = path.trim();
            if (!path.startsWith("/")) {
                path = "/" + path;
            }

            // First check if the supplied path is a dialog node itself
            if (session.nodeExists(path)) {
                Node node = session.getNode(path);
                DialogType type = DialogRewriteUtils.getDialogType(node);

                if (type != DialogType.UNKNOWN && type != DialogType.CORAL_3) {
                    nodeMap.put(node.getPath(), node);
                }
            }

            // If the path does not point to a dialog node: we query for dialog nodes
            if (nodeMap.isEmpty()) {
                String encodedPath = "/".equals(path) ? "" : ISO9075.encodePath(path);
                if (encodedPath.length() > 1 && encodedPath.endsWith("/")) {
                    encodedPath = encodedPath.substring(0, encodedPath.length() - 1);
                }
                String classicUIStatement = "SELECT * FROM [" + NT_DIALOG + "] AS s WHERE ISDESCENDANTNODE(s, '" + encodedPath + "') AND NAME() = '" + NN_DIALOG + "'";
                String coral2Statement = "SELECT parent.* FROM [nt:unstructured] AS parent INNER JOIN [nt:unstructured] " +
                        "AS child on ISCHILDNODE(child, parent) WHERE ISDESCENDANTNODE(parent, '" + encodedPath + "') AND NAME(parent) = '" + NN_CQ_DIALOG + "' " +
                        "AND NAME(child) = 'content' AND child.[sling:resourceType] NOT LIKE '" + DIALOG_CONTENT_RESOURCETYPE_PREFIX_CORAL3 + "%'";

                QueryManager queryManager = session.getWorkspace().getQueryManager();
                List<Query> queries = new ArrayList<Query>();
                queries.add(queryManager.createQuery(classicUIStatement, Query.JCR_SQL2));
                queries.add(queryManager.createQuery(coral2Statement, Query.JCR_SQL2));

                for (Query query : queries) {
                    NodeIterator iterator = query.execute().getNodes();
                    while (iterator.hasNext()) {
                        Node node = iterator.nextNode();
                        Node parent = node.getParent();
                        if (parent != null) {
                            nodeMap.put(parent.getPath(), node);
                        }
                    }
                }
            }

            int index = 0;
            Iterator iterator = nodeMap.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry entry = (Map.Entry)iterator.next();
                Node dialog = (Node)entry.getValue();

                if (dialog == null) {
                    continue;
                }

                Node parent = dialog.getParent();

                if (parent == null) {
                    continue;
                }

                DialogType dialogType = DialogRewriteUtils.getDialogType(dialog);

                String dialogPath = dialog.getPath();
                String type = dialogType.getString();
                String href = externalizer.relativeLink(request, dialogPath) + ".html";
                String crxHref = externalizer.relativeLink(request, CRX_LITE_PATH) + ".jsp#" + dialogPath;
                boolean converted = (dialogType == DialogType.CLASSIC) && parent.hasNode(NN_CQ_DIALOG);

                Map<String, Object> map = new HashMap<String, Object>();
                map.put("dialogPath", dialogPath);
                map.put("type", type);
                map.put("href", href);
                map.put("converted", converted);
                map.put("crxHref", crxHref);

                if (converted) {
                    Node touchDialogNode = parent.getNode(NN_CQ_DIALOG);
                    String touchHref = externalizer.relativeLink(request, touchDialogNode.getPath()) + ".html";
                    String touchCrxHref = externalizer.relativeLink(request, CRX_LITE_PATH) + ".jsp#" + touchDialogNode.getPath().replaceAll(":", "%3A");

                    map.put("touchHref", touchHref);
                    map.put("touchCrxHref", touchCrxHref);
                }

                resources.add(new ValueMapResource(resourceResolver, resource.getPath() + "/dialog_" + index, itemResourceType, new ValueMapDecorator(map)));
                index++;
            }
        }

        DataSource ds = new SimpleDataSource(resources.iterator());

        request.setAttribute(DataSource.class.getName(), ds);
    }
}
