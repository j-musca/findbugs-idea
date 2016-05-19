/*
 * Copyright 2016 Andre Pfeiler
 *
 * This file is part of FindBugs-IDEA.
 *
 * FindBugs-IDEA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FindBugs-IDEA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with FindBugs-IDEA.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.twodividedbyzero.idea.findbugs.plugins;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.twodividedbyzero.idea.findbugs.common.util.New;
import org.twodividedbyzero.idea.findbugs.core.ProjectSettings;

import java.lang.ref.WeakReference;

public final class PluginLoader {
	private static WeakReference<Project> projectRef; // @GuardedBy PluginLoader.class

	private PluginLoader() {
	}

	synchronized static void invalidate() {
		projectRef = null;
	}

	public synchronized static boolean load(
			@NotNull final Project project,
			@NotNull final ProjectSettings settings,
			final boolean addEditSettingsLinkToErrorMessage
	) {

		Project latestProject = projectRef == null ? null : projectRef.get();
		if (latestProject != project) {
			final PluginLoaderImpl pluginLoader = new PluginLoaderImpl(addEditSettingsLinkToErrorMessage);
			pluginLoader.load(settings.plugins);
			projectRef = New.weakRef(project);
			return pluginLoader.showErrorNotificationIfNecessary(project);
		}
		return true;
	}

	private static class PluginLoaderImpl extends AbstractPluginLoader {
		PluginLoaderImpl(final boolean addEditSettingsLinkToErrorMessage) {
			super(addEditSettingsLinkToErrorMessage);
		}
	}
}