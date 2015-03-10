/*******************************************************************************
 * Copyright (c) 2004, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.codenvy.ide.ext.java.server.internal.core.search;

import com.codenvy.ide.ext.java.server.core.search.IJavaSearchScope;
import com.codenvy.ide.ext.java.server.core.search.SearchPattern;
import com.codenvy.ide.ext.java.server.internal.core.search.indexing.IndexManager;
import com.codenvy.ide.ext.java.server.internal.core.search.matching.MatchLocator;
import com.codenvy.ide.ext.java.server.internal.core.search.matching.MethodPattern;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.util.ObjectVector;
import org.eclipse.jdt.internal.compiler.util.SimpleSet;
import org.eclipse.jdt.internal.core.JarPackageFragmentRoot;
import org.eclipse.jdt.internal.core.JavaModel;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.internal.core.builder.ReferenceCollection;
import org.eclipse.jdt.internal.core.builder.State;
import org.eclipse.jdt.internal.core.index.IndexLocation;

import java.io.File;
import java.util.LinkedHashSet;

/**
 * Selects the indexes that correspond to projects in a given search scope
 * and that are dependent on a given focus element.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class IndexSelector {

	//TODO: Bug 386113: "Search references" and "Type hierarchy" show inconsistent results with "External Plug-in Libraries" project
	public static final int PROJECT_CAN_SEE_FOCUS            = 0;
	public static final int PROJECT_SOURCE_CAN_NOT_SEE_FOCUS = 1;
	public static final int PROJECT_CAN_NOT_SEE_FOCUS        = 2;

	IJavaSearchScope searchScope;
	SearchPattern    pattern;
	IndexLocation[] indexLocations; // cache of the keys for looking index up
	private IndexManager indexManager;

	public IndexSelector(
			IJavaSearchScope searchScope,
			SearchPattern pattern, IndexManager indexManager) {

		this.searchScope = searchScope;
		this.pattern = pattern;
		this.indexManager = indexManager;
	}

	/**
	 * Returns whether elements of the given project or jar can see the given focus (an IJavaProject or
	 * a JarPackageFragmentRot) either because the focus is part of the project or the jar, or because it is
	 * accessible throught the project's classpath
	 */
	public static int canSeeFocus(SearchPattern pattern, IPath projectOrJarPath) {
		try {
			IJavaModel model = JavaModelManager.getJavaModelManager().getJavaModel();
			IJavaProject project = getJavaProject(projectOrJarPath, model);
			IJavaElement[] focuses = getFocusedElementsAndTypes(pattern, project, null);
			if (focuses.length == 0) return PROJECT_CAN_NOT_SEE_FOCUS;
			if (project != null) {
				return canSeeFocus(focuses, (JavaProject)project, null);
			}

			// projectOrJarPath is a jar
			// it can see the focus only if it is on the classpath of a project that can see the focus
			int result = PROJECT_CAN_NOT_SEE_FOCUS;
			IJavaProject[] allProjects = model.getJavaProjects();
			for (int i = 0, length = allProjects.length; i < length; i++) {
				JavaProject otherProject = (JavaProject)allProjects[i];
				IClasspathEntry entry = otherProject.getClasspathEntryFor(projectOrJarPath);
				if (entry != null && entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
					int canSeeFocus = canSeeFocus(focuses, otherProject, null);
					if (canSeeFocus == PROJECT_CAN_SEE_FOCUS)
						return PROJECT_CAN_SEE_FOCUS;
					if (canSeeFocus == PROJECT_SOURCE_CAN_NOT_SEE_FOCUS)
						result = PROJECT_SOURCE_CAN_NOT_SEE_FOCUS;
				}
			}
			return result;
		} catch (JavaModelException e) {
			return PROJECT_CAN_NOT_SEE_FOCUS;
		}
	}

	private static int canSeeFocus(IJavaElement[] focuses, JavaProject javaProject, char[][][] focusQualifiedNames) {
		int result = PROJECT_CAN_NOT_SEE_FOCUS;
		int length = focuses.length;
		for (int i = 0; i < length; i++) {
			int canSeeFocus = canSeeFocus(focuses[i], javaProject, focusQualifiedNames);
			if (canSeeFocus == PROJECT_CAN_SEE_FOCUS)
				return PROJECT_CAN_SEE_FOCUS;
			if (canSeeFocus == PROJECT_SOURCE_CAN_NOT_SEE_FOCUS)
				result = PROJECT_SOURCE_CAN_NOT_SEE_FOCUS;
		}
		return result;
	}

	private static int canSeeFocus(IJavaElement focus, JavaProject javaProject, char[][][] focusQualifiedNames) {
		try {
			if (focus == null) return PROJECT_CAN_NOT_SEE_FOCUS;
			if (focus.equals(javaProject)) return PROJECT_CAN_SEE_FOCUS;

		if (focus instanceof JarPackageFragmentRoot) {
			// focus is part of a jar
			IPath focusPath = focus.getPath();
			IClasspathEntry[] entries = javaProject.getExpandedClasspath();
			for (int i = 0, length = entries.length; i < length; i++) {
				IClasspathEntry entry = entries[i];
				if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY && entry.getPath().equals(focusPath))
					return PROJECT_CAN_SEE_FOCUS;
			}
			return PROJECT_CAN_NOT_SEE_FOCUS;
		}
		// look for dependent projects
		IPath focusPath = ((JavaProject) focus).getProject().getFullPath();
		IClasspathEntry[] entries = javaProject.getExpandedClasspath();
		for (int i = 0, length = entries.length; i < length; i++) {
			IClasspathEntry entry = entries[i];
			if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT && entry.getPath().equals(focusPath)) {
				if (focusQualifiedNames != null) { // builder state is usable, hence use it to try to reduce project which can see the focus...
					State projectState = (State) JavaModelManager.getJavaModelManager().getLastBuiltState(javaProject.getProject(), null);
					if (projectState != null) {
						Object[] values = projectState.getReferences().valueTable;
						int vLength = values.length;
						for (int j=0; j<vLength; j++)  {
							if (values[j] == null) continue;
							ReferenceCollection references = (ReferenceCollection) values[j];
							if (references.includes(focusQualifiedNames, null, null)) {
								return PROJECT_CAN_SEE_FOCUS;
							}
						}
						return PROJECT_SOURCE_CAN_NOT_SEE_FOCUS;
					}
				}
				return PROJECT_CAN_SEE_FOCUS;
			}
		}
			return PROJECT_CAN_NOT_SEE_FOCUS;
	} catch (JavaModelException e) {
			return PROJECT_CAN_NOT_SEE_FOCUS;
	}
}

/*
 * Create the list of focused jars or projects.
 */
private static IJavaElement[] getFocusedElementsAndTypes(SearchPattern pattern, IJavaElement focusElement, ObjectVector superTypes) throws
																																	JavaModelException {
	if (pattern instanceof MethodPattern) {
		// For method pattern, it needs to walk along the focus type super hierarchy
		// and add jars/projects of all the encountered types.
		IType type = (IType) pattern.focus.getAncestor(IJavaElement.TYPE);
		MethodPattern methodPattern = (MethodPattern) pattern;
		String selector = new String(methodPattern.selector);
		int parameterCount = methodPattern.parameterCount;
		ITypeHierarchy superHierarchy = type.newSupertypeHierarchy(null);
		IType[] allTypes = superHierarchy.getAllSupertypes(type);
		int length = allTypes.length;
		SimpleSet focusSet = new SimpleSet(length+1);
		if (focusElement != null) focusSet.add(focusElement);
		for (int i=0; i<length; i++) {
			IMethod[] methods = allTypes[i].getMethods();
			int mLength = methods.length;
			for (int m=0; m<mLength; m++) {
				if (parameterCount == methods[m].getNumberOfParameters() && methods[m].getElementName().equals(selector)) {
					IPackageFragmentRoot root = (IPackageFragmentRoot) allTypes[i].getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
					IJavaElement element = root.isArchive() ? root : root.getParent();
					focusSet.add(element);
					if (superTypes != null) superTypes.add(allTypes[i]);
					break;
				}
			}
		}
		// Rebuilt a contiguous array
		IJavaElement[] focuses = new IJavaElement[focusSet.elementSize];
		Object[] values = focusSet.values;
		int count = 0;
		for (int i = values.length; --i >= 0;) {
			if (values[i] != null) {
				focuses[count++] = (IJavaElement) values[i];
			}
		}
		return focuses;
	}
	if (focusElement == null) return new IJavaElement[0];
	return new IJavaElement[] { focusElement };
}

	/**
	 * Returns the java project that corresponds to the given path.
	 * Returns null if the path doesn't correspond to a project.
	 */
	private static IJavaProject getJavaProject(IPath path, IJavaModel model) {
		IJavaProject project = model.getJavaProject(path.lastSegment());
		if (project.exists()) {
			return project;
		}
		return null;
	}

	/*
     *  Compute the list of paths which are keying index files.
     */
private void initializeIndexLocations() {
	IPath[] projectsAndJars = this.searchScope.enclosingProjectsAndJars();
	// use a linked set to preserve the order during search: see bug 348507
	LinkedHashSet locations = new LinkedHashSet();
	IJavaElement focus = MatchLocator.projectOrJarFocus(this.pattern);
	if (focus == null) {
		for (int i = 0; i < projectsAndJars.length; i++) {
			IPath path = projectsAndJars[i];
			Object target = new File(path.toOSString());//JavaModel.getTarget(path, false/*don't check existence*/);
			if (target instanceof IFolder) // case of an external folder
				path = ((IFolder) target).getFullPath();
			locations.add(indexManager.computeIndexLocation(path));
		}
	} else {
		try {
			// See whether the state builder might be used to reduce the number of index locations

			// find the projects from projectsAndJars that see the focus then walk those projects looking for the jars from projectsAndJars
			int length = projectsAndJars.length;
			JavaProject[] projectsCanSeeFocus = new JavaProject[length];
			SimpleSet visitedProjects = new SimpleSet(length);
			int projectIndex = 0;
			SimpleSet externalLibsToCheck = new SimpleSet(length);
			ObjectVector superTypes = new ObjectVector();
			IJavaElement[] focuses = getFocusedElementsAndTypes(this.pattern, focus, superTypes);
			char[][][] focusQualifiedNames = null;
			boolean isAutoBuilding = ResourcesPlugin.getWorkspace().getDescription().isAutoBuilding();
			if (isAutoBuilding && focus instanceof IJavaProject) {
				focusQualifiedNames = getQualifiedNames(superTypes);
			}
			IJavaModel model = JavaModelManager.getJavaModelManager().getJavaModel();
			for (int i = 0; i < length; i++) {
				IPath path = projectsAndJars[i];
				JavaProject project = (JavaProject) getJavaProject(path, model);
				if (project != null) {
					visitedProjects.add(project);
					int canSeeFocus = canSeeFocus(focuses, project, focusQualifiedNames);
					if (canSeeFocus == PROJECT_CAN_SEE_FOCUS) {
						locations.add(indexManager.computeIndexLocation(path));
					}
					if (canSeeFocus != PROJECT_CAN_NOT_SEE_FOCUS) {
						projectsCanSeeFocus[projectIndex++] = project;
					}
				} else {
					externalLibsToCheck.add(path);
				}
			}
			for (int i = 0; i < projectIndex && externalLibsToCheck.elementSize > 0; i++) {
				IClasspathEntry[] entries = projectsCanSeeFocus[i].getResolvedClasspath();
				for (int j = entries.length; --j >= 0;) {
					IClasspathEntry entry = entries[j];
					if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
						IPath path = entry.getPath();
						if (externalLibsToCheck.remove(path) != null) {
							Object target = JavaModel.getTarget(path, false/*don't check existence*/);
							if (target instanceof IFolder) // case of an external folder
								path = ((IFolder) target).getFullPath();
							locations.add(indexManager.computeIndexLocation(path));
						}
					}
				}
			}
			// jar files can be included in the search scope without including one of the projects that references them, so scan all projects that have not been visited
			if (externalLibsToCheck.elementSize > 0) {
				IJavaProject[] allProjects = model.getJavaProjects();
				for (int i = 0, l = allProjects.length; i < l && externalLibsToCheck.elementSize > 0; i++) {
					JavaProject project = (JavaProject) allProjects[i];
					if (!visitedProjects.includes(project)) {
						IClasspathEntry[] entries = project.getResolvedClasspath();
						for (int j = entries.length; --j >= 0;) {
							IClasspathEntry entry = entries[j];
							if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY) {
								IPath path = entry.getPath();
								if (externalLibsToCheck.remove(path) != null) {
									Object target = JavaModel.getTarget(path, false/*don't check existence*/);
									if (target instanceof IFolder) // case of an external folder
										path = ((IFolder) target).getFullPath();
									locations.add(indexManager.computeIndexLocation(path));
								}
							}
						}
					}
				}
			}
		} catch (JavaModelException e) {
			// ignored
		}
	}

	locations.remove(null); // Ensure no nulls
	this.indexLocations = (IndexLocation[]) locations.toArray(new IndexLocation[locations.size()]);
}

public IndexLocation[] getIndexLocations() {
	if (this.indexLocations == null) {
		initializeIndexLocations();
	}
	return this.indexLocations;
}

private char[][][] getQualifiedNames(ObjectVector types) {
	final int size = types.size;
	char[][][] focusQualifiedNames = null;
	IJavaElement javaElement = this.pattern.focus;
	int index = 0;
	while (javaElement != null && !(javaElement instanceof ITypeRoot)) {
		javaElement = javaElement.getParent();
	}
	if (javaElement != null) {
		IType primaryType = ((ITypeRoot) javaElement).findPrimaryType();
		if (primaryType != null) {
			focusQualifiedNames = new char[size+1][][];
			focusQualifiedNames[index++] = CharOperation.splitOn('.', primaryType.getFullyQualifiedName().toCharArray());
		} 
	}
	if (focusQualifiedNames == null) {
		focusQualifiedNames = new char[size][][];
	}
	for (int i = 0; i < size; i++) {
		focusQualifiedNames[index++] = CharOperation.splitOn('.', ((IType)(types.elementAt(i))).getFullyQualifiedName().toCharArray());
	}
	return focusQualifiedNames.length == 0 ? null : ReferenceCollection.internQualifiedNames(focusQualifiedNames, true);
}
}