/*******************************************************************************
 * Copyright (c) 2012-2014 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/

package com.codenvy.ide.ext.java.client.navigation;

import com.codenvy.ide.collections.Array;
import com.codenvy.ide.ext.java.shared.Jar;
import com.codenvy.ide.ext.java.shared.JarEntry;
import com.codenvy.ide.rest.AsyncRequestCallback;
import com.codenvy.ide.rest.AsyncRequestFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

/**
 * @author Evgen Vidolob
 */
@Singleton
public class JavaNavigationServiceImpl implements JavaNavigationService {

    private final String              restContext;
    private final String              workspaceId;
    private       AsyncRequestFactory requestFactory;

    @Inject
    public JavaNavigationServiceImpl(@Named("javaCA") String restContext,
                                     @Named("workspaceId") String workspaceId,
                                     AsyncRequestFactory asyncRequestFactory) {
        this.restContext = restContext;
        this.workspaceId = workspaceId;
        this.requestFactory = asyncRequestFactory;
    }

    @Override
    public void findDeclaration(String projectPath, String keyBinding, AsyncRequestCallback<String> callback) {
        String url =
                restContext + "/navigation/" + workspaceId + "/find-declaration?projectpath=" + projectPath + "&bindingkey=" + keyBinding;
        requestFactory.createGetRequest(url).send(callback);
    }

    public void getExternalLibraries(String projectPath, AsyncRequestCallback<Array<Jar>> callback){
        String url =
                restContext + "/navigation/" + workspaceId + "/libraries?projectpath=" + projectPath;
        requestFactory.createGetRequest(url).send(callback);
    }

    @Override
    public void getLibraryChildren(String projectPath, int libId, AsyncRequestCallback<Array<JarEntry>> callback) {
        String url =
                restContext + "/navigation/" + workspaceId + "/lib/children?projectpath=" + projectPath + "&root=" + libId;
        requestFactory.createGetRequest(url).send(callback);
    }

    @Override
    public void getChildren(String projectPath, int libId, String path, AsyncRequestCallback<Array<JarEntry>> callback) {
        String url =
                restContext + "/navigation/" + workspaceId + "/children?projectpath=" + projectPath + "&root=" + libId + "&path=" + path;
        requestFactory.createGetRequest(url).send(callback);
    }
}
