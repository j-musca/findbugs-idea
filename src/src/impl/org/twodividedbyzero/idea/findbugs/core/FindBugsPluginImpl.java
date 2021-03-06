/*
 * Copyright 2008-2016 Andre Pfeiler
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
package org.twodividedbyzero.idea.findbugs.core;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.ui.search.SearchableOptionsRegistrar;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Anchor;
import com.intellij.openapi.actionSystem.Constraints;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StorageScheme;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.psi.PsiFile;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugPattern;
import edu.umd.cs.findbugs.DetectorFactory;
import edu.umd.cs.findbugs.FindBugs;
import edu.umd.cs.findbugs.Plugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.ba.AnalysisException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.twodividedbyzero.idea.findbugs.actions.AnalyzeCurrentEditorFile;
import org.twodividedbyzero.idea.findbugs.common.ExtendedProblemDescriptor;
import org.twodividedbyzero.idea.findbugs.common.FindBugsPluginConstants;
import org.twodividedbyzero.idea.findbugs.common.FindBugsPluginUtil;
import org.twodividedbyzero.idea.findbugs.common.VersionManager;
import org.twodividedbyzero.idea.findbugs.common.exception.FindBugsPluginException;
import org.twodividedbyzero.idea.findbugs.common.util.FindBugsUtil;
import org.twodividedbyzero.idea.findbugs.common.util.IdeaUtilImpl;
import org.twodividedbyzero.idea.findbugs.gui.common.BalloonTipFactory;
import org.twodividedbyzero.idea.findbugs.gui.preferences.AnnotationType;
import org.twodividedbyzero.idea.findbugs.gui.preferences.ConfigurationPanel;
import org.twodividedbyzero.idea.findbugs.gui.toolwindow.view.ToolWindowPanel;
import org.twodividedbyzero.idea.findbugs.messages.MessageBusManager;
import org.twodividedbyzero.idea.findbugs.plugins.AbstractPluginLoaderLegacy;
import org.twodividedbyzero.idea.findbugs.plugins.Plugins;
import org.twodividedbyzero.idea.findbugs.preferences.FindBugsPreferences;
import org.twodividedbyzero.idea.findbugs.preferences.PersistencePreferencesBean;
import org.twodividedbyzero.idea.findbugs.resources.GuiResources;
import org.twodividedbyzero.idea.findbugs.resources.ResourcesLoader;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * $Date$
 *
 * @author Andre Pfeiler<andrepdo@dev.java.net>
 * @since 0.1.1
 */
@SuppressWarnings({"HardcodedFileSeparator", "RedundantInterfaceDeclaration"})
@State(
		name = FindBugsPluginConstants.PLUGIN_ID,
		storages = {
				@Storage(id = "other", file = "$PROJECT_FILE$"),
				@Storage(id = "dir", file = "$PROJECT_CONFIG_DIR$/findbugs-idea.xml", scheme = StorageScheme.DIRECTORY_BASED)})
@Deprecated
public class FindBugsPluginImpl implements ProjectComponent, FindBugsPlugin, SearchableConfigurable, PersistentStateComponent<PersistencePreferencesBean> {

	// idea_home/bin/log.xml
	/*<category name="org.twodividedbyzero.idea.findbugs">
   		<priority value="DEBUG" />
   		<appender-ref ref="CONSOLE-DEBUG"/>
   		<appender-ref ref="FILE"/>
	</category>*/
	private static final Logger LOGGER = Logger.getInstance(FindBugsPluginImpl.class.getName());
	private static final AtomicBoolean SEARCH_INDEX_CREATED = new AtomicBoolean(false);

	private final Project _project;
	private ToolWindow _toolWindow;
	private static final Set<AnAction> _mainToolbarActions;
	private static final Set<AnAction> _registeredMainToolbarActions;

	private ConfigurationPanel _configPanel;
	private FindBugsPreferences _preferences;


	static {
		final IdeaPluginDescriptor plugin = FindBugsPluginUtil.getIdeaPluginDescriptor();
		FindBugs.setHome(plugin.getPath().toURI().toString());
		Plugins.deploy(plugin);

		_registeredMainToolbarActions = new HashSet<AnAction>();
		_mainToolbarActions = new HashSet<AnAction>();
		final AnAction action = new AnalyzeCurrentEditorFile();
		_mainToolbarActions.add(action);
		action.getTemplatePresentation().setText("Run FindBugs analysis on the current editor file", true);
		action.getTemplatePresentation().setIcon(GuiResources.FINDBUGS_EXECUTE_ICON);
	}


	//@State


	public FindBugsPluginImpl(final Project project) {
		_project = project;

		try {
			if (project != null) {
				LOGGER.info(VersionManager.getFullVersion() + " Plugin loaded with project base dir: " + IdeaUtilImpl.getProjectPath(project));
				LOGGER.info("using Findbugs version " + FindBugsUtil.getFindBugsFullVersion());
			} else {
				LOGGER.info(VersionManager.getFullVersion() + " Plugin loaded with no project.");
			}

		} catch (final Throwable t) {
			LOGGER.error("Project initialisation failed.", t);
		}

	}


	@SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
	@Override
	public void initComponent() {
		final IdeaPluginDescriptor plugin = PluginManager.getPlugin(PluginId.getId(FindBugsPluginConstants.PLUGIN_NAME));
		//noinspection ConstantConditions
		LOGGER.debug("initComponent: " + plugin.getName() + " project=" + getProject());
	}


	@SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
	@Override
	public void disposeComponent() {
		final IdeaPluginDescriptor plugin = PluginManager.getPlugin(PluginId.getId(FindBugsPluginConstants.PLUGIN_NAME));
		//noinspection ConstantConditions
		LOGGER.debug("disposeComponent: " + plugin.getName() + " project=" + getProject());
	}


	@SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE")
	@Override
	@NotNull
	public String getComponentName() {
		return FindBugsPluginConstants.PLUGIN_ID;
	}


	/**
	 * Invoked by EDT.
	 */
	@SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
	@Override
	public void projectOpened() {
		final IdeaPluginDescriptor plugin = PluginManager.getPlugin(PluginId.getId(FindBugsPluginConstants.PLUGIN_NAME));
		//noinspection ConstantConditions
		LOGGER.debug("project is opened: " + plugin.getName() + " project=" + getProject());
		initToolWindow();
		setActionGroupsIcon();
		registerToolbarActions();
	}


	/**
	 * Invoked by EDT.
	 */
	@SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
	@Override
	public void projectClosed() {
		final IdeaPluginDescriptor plugin = PluginManager.getPlugin(PluginId.getId(FindBugsPluginConstants.PLUGIN_NAME));
		//noinspection ConstantConditions
		LOGGER.debug("project is being closed: " + plugin.getName() + " project=" + getProject());
		MessageBusManager.dispose(_project);
		unregisterToolWindow();
		disableToolbarActions();
		FindBugsState.dispose(_project);
	}


	private void initToolWindow() {
		final ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(_project);

		_toolWindow = toolWindowManager.registerToolWindow(FindBugsPluginConstants.TOOL_WINDOW_ID, false, ToolWindowAnchor.BOTTOM);
		_toolWindow.setTitle(FindBugsPluginConstants.TOOL_WINDOW_ID);
		_toolWindow.setType(ToolWindowType.DOCKED, null);


		final ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();

		final JComponent toolWindowPanel = new ToolWindowPanel(_project, _toolWindow);
		final Content content = contentFactory.createContent(toolWindowPanel, "FindBugs Analysis Results", false);

		_toolWindow.getContentManager().addContent(content);
		_toolWindow.setIcon(GuiResources.FINDBUGS_ICON_13X13);
	}


	private void unregisterToolWindow() {
		final ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(_project);
		toolWindowManager.unregisterToolWindow(FindBugsPluginConstants.TOOL_WINDOW_ID);
	}


	@Override
	public Project getProject() {
		return _project;
	}


	@Override
	public Module getModule() {
		return null;
	}


	@Override
	public boolean isModuleComponent() {
		return false;
	}


	@Override
	public ToolWindowPanel getToolWindowPanel() {
		final Content content = _toolWindow.getContentManager().getContent(0);
		if (content != null) {
			return (ToolWindowPanel) content.getComponent();
		}
		return null;
	}


	@Override
	public void activateToolWindow(final boolean activate) {
		if (activate) {
			_toolWindow.show(null);
		} else {
			_toolWindow.hide(null);
		}
	}


	@Override
	public BugCollection getBugCollection() {
		throw new RuntimeException("Deprecated");
	}


	@Override
	public Map<PsiFile, List<ExtendedProblemDescriptor>> getProblems() {
		return getToolWindowPanel().getProblems();
	}


	private static void registerToolbarActions() {
		final DefaultActionGroup mainToolbar = (DefaultActionGroup) ActionManager.getInstance().getAction("MainToolBar");
		for (final AnAction anAction : _mainToolbarActions) {
			if (!isActionRegistered(anAction)) {
				_registeredMainToolbarActions.add(anAction);
				mainToolbar.add(anAction, new Constraints(Anchor.BEFORE, "RunConfiguration"));
			}
		}
	}


	@SuppressWarnings({"MethodMayBeStatic"})
	private void setActionGroupsIcon() {
		final AnAction findBugsEditorPopup = ActionManager.getInstance().getAction("FindBugs.EditorPopup");
		findBugsEditorPopup.getTemplatePresentation().setIcon(GuiResources.FINDBUGS_ICON);

		final AnAction findBugsProjectViewPopup = ActionManager.getInstance().getAction("FindBugs.ProjectViewPopupMenu");
		findBugsProjectViewPopup.getTemplatePresentation().setIcon(GuiResources.FINDBUGS_ICON);

		final AnAction findBugsAnalyzeMenu = ActionManager.getInstance().getAction("FindBugs.AnalyzeMenu");
		findBugsAnalyzeMenu.getTemplatePresentation().setIcon(GuiResources.FINDBUGS_ICON);
	}


	private static void disableToolbarActions() {
		for (final AnAction action : _mainToolbarActions) {
			action.getTemplatePresentation().setEnabled(false);
		}
	}


	private static boolean isActionRegistered(final AnAction anAction) {
		return _registeredMainToolbarActions.contains(anAction);
	}


	/**
	 * Process an error.
	 *
	 * @param message a description of the error. May be null.
	 * @param error   the exception.
	 * @return any exception to be passed upwards.
	 */
	@SuppressWarnings("unused")
	@SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE")
	@NotNull
	public static FindBugsPluginException processError(final String message, @Nullable final Throwable error) {
		Throwable root = error;

		while (root != null && root.getCause() != null && !(root instanceof AnalysisException)) {
			root = root.getCause();
		}

		if (message != null) {
			return new FindBugsPluginException(message, root);
		}

		if (root != null) {
			return new FindBugsPluginException(root.getMessage(), root);
		}

		return new FindBugsPluginException("Unknown error..."); // this should never happen
	}


	@SuppressWarnings({"ThrowableResultOfMethodCallIgnored", "UnusedParameters"})
	public static void processError(final Map<String, Map<String, Throwable>> status) {
	}


	public static void showToolWindowNotifier(@NotNull final Project project, final String message, final MessageType type) {
		if (MessageType.INFO.equals(type)) {
			BalloonTipFactory.showToolWindowInfoNotifier(project, message);
		} else if (MessageType.WARNING.equals(type)) {
			BalloonTipFactory.showToolWindowWarnNotifier(project, message);
		} else if (MessageType.ERROR.equals(type)) {
			BalloonTipFactory.showToolWindowErrorNotifier(project, message);
		}
	}


	@Override
	public FindBugsPreferences getPreferences() {
		if (_preferences == null) {
			_preferences = getEmptyPreferences(
					Collections.<String>emptyList(),
					Collections.<String>emptyList(),
					Collections.<String>emptyList(),
					Collections.<String>emptyList(),
					Collections.<String>emptyList(),
					null
			);
		}
		//noinspection ReturnOfCollectionOrArrayField
		return _preferences;
	}


	/**
	 * ==========================================================================
	 * Plugin Configuration
	 */

	@Nls
	@Override
	public String getDisplayName() {
		return ResourcesLoader.getString("findbugs.plugin.configuration.name");
	}


	public Icon getIcon() {
		return GuiResources.FINDBUGS_CONFIGURATION_ICON;
	}


	@Override
	public String getHelpTopic() {
		return FindBugsPluginConstants.FINDBUGS_EXTERNAL_HELP_URI;
	}


	@Override
	public JComponent createComponent() {
		if (_configPanel == null) {
			_configPanel = new ConfigurationPanel(this);
		}
		return _configPanel;
	}


	@Override
	public boolean isModified() {
		return _preferences.isModified();
	}


	@Override
	public void apply() throws ConfigurationException {

		final PluginLoaderImpl pluginLoader = new PluginLoaderImpl();
		pluginLoader.load(_preferences.getPlugins(), _preferences.getDisabledUserPluginIds(), _preferences.getEnabledBundledPluginIds(), _preferences.getDisabledBundledPluginIds());

		final boolean analyzeAfterAutomake = getPreferences().getBooleanProperty(FindBugsPreferences.ANALYZE_AFTER_AUTOMAKE, false);
		FindBugsCompileAfterHook.setAnalyzeAfterAutomake(_project, analyzeAfterAutomake);

		_preferences.applyDetectors();
		_configPanel.updatePreferences(); // at least DetectorConfiguration needs a reload
		_preferences.setModified(false);
	}


	@Override
	public void reset() {
	}


	@Override
	public void disposeUIResources() {
		if (_configPanel != null) {
			_configPanel.setVisible(false);
		}
		//noinspection AssignmentToNull
		_configPanel = null;
	}


	private synchronized FindBugsPreferences getDefaultPreferences() {
		if (_preferences == null) {
			_preferences = FindBugsPreferences.createDefault(_project, true);
		}
		return _preferences;
	}


	private synchronized FindBugsPreferences getEmptyPreferences(
			final List<String> plugins,
			final Collection<String> enabledUserPluginIds,
			final Collection<String> disabledUserPluginIds,
			final Collection<String> enabledBundledPluginIds,
			final Collection<String> disabledBundledPluginIds,
			@Nullable final Map<String, String> detectors
	) {
		if (_preferences == null) {
			_preferences = FindBugsPreferences.createEmpty(_project, true, plugins, enabledUserPluginIds, disabledUserPluginIds, enabledBundledPluginIds, disabledBundledPluginIds, detectors);
		} else {
			_preferences.loadPlugins(_project, plugins, enabledUserPluginIds, disabledUserPluginIds, enabledBundledPluginIds, disabledBundledPluginIds, detectors);
		}
		return _preferences;
	}


	@Override
	public void loadState(final PersistencePreferencesBean state) {
		if (!state.isEmpty()) {
			final Map<String, String> detectors = state.getDetectors();

			_preferences = getEmptyPreferences(state.getPlugins(), state.getEnabledUserPluginIds(), state.getDisabledUserPluginIds(), state.getEnabledBundledPluginIds(), state.getDisabledBundledPluginIds(), detectors);

			for (final String key : state.getBasePreferences().keySet()) {
				_preferences.setProperty(key, state.getBasePreferences().get(key));
			}

			_preferences.setDetectors(detectors);
			for (final Entry<String, String> entry : detectors.entrySet()) {
				final DetectorFactory detectorFactory = FindBugsPreferences.getDetectorFactorCollection().getFactory(entry.getKey());
				if (detectorFactory != null) {
					_preferences.getUserPreferences().enableDetector(detectorFactory, Boolean.valueOf(entry.getValue()));
				}
			}

			_preferences.setBugCategories(state.getBugCategories());
			_preferences.setIncludeFilters(state.getIncludeFilters());
			_preferences.setExcludeFilters(state.getExcludeFilters());
			_preferences.setExcludeBaselineBugs(state.getExcludeBaselineBugs());

			_preferences.setEnabledModuleConfigs(state.getEnabledModuleConfigs());

			if (_preferences.getBugCategories().isEmpty()) {
				_preferences.setBugCategories(FindBugsPreferences.getDefaultBugCategories(_preferences.getUserPreferences().getFilterSettings()));
			}
			if (_preferences.getDetectors().isEmpty()) {
				LOGGER.debug("empty detectors loading defaults.");
				_preferences.setDetectors(FindBugsPreferences.getAvailableDetectors(_preferences.getUserPreferences()));
			}

			_preferences.setAnnotationSuppressWarningsClass(state.getAnnotationSuppressWarningsClass());
			_preferences.setAnnotationGutterIconEnabled(state.isAnnotationGutterIconEnabled());
			_preferences.setAnnotationTextRangeMarkupEnabled(state.isAnnotationTextRangeMarkupEnabled());
			_preferences.setFlattendAnnotationTypeSettings(state.getAnnotationTypeSettings());
			if (_preferences.getAnnotationTypeSettings().isEmpty()) {
				_preferences.setAnnotationTypeSettings(FindBugsPreferences.createDefaultAnnotationTypeSettings());
			}
			AnnotationType.configureFrom(_preferences.getAnnotationTypeSettings());

		} else {
			_preferences.clear();
			_preferences = FindBugsPreferences.createDefault(_project, true);
		}
		_preferences.setModified(false); // make sure not modified at the end of loading
		buildSearchIndexIfNecessary();
	}


	@Override
	public PersistencePreferencesBean getState() {
		final PersistencePreferencesBean preferencesBean = new PersistencePreferencesBean();
		if (_preferences == null) {
			_preferences = getDefaultPreferences();
		}

		//noinspection ForLoopWithMissingComponent
		for (final Enumeration<?> confNames = _preferences.propertyNames(); confNames.hasMoreElements(); ) {
			final String elementName = (String) confNames.nextElement();
			preferencesBean.getBasePreferences().put(elementName, _preferences.getProperty(elementName));
		}
		preferencesBean.getDetectors().putAll(_preferences.getDetectors());
		preferencesBean.getBugCategories().putAll(_preferences.getBugCategories());

		preferencesBean.getIncludeFilters().addAll(_preferences.getIncludeFilters());
		preferencesBean.getExcludeFilters().addAll(_preferences.getExcludeFilters());
		preferencesBean.getExcludeBaselineBugs().addAll(_preferences.getExcludeBaselineBugs());
		preferencesBean.getPlugins().addAll(_preferences.getPlugins());
		preferencesBean.getEnabledUserPluginIds().addAll(_preferences.getEnabledUserPluginIds());
		preferencesBean.getDisabledUserPluginIds().addAll(_preferences.getDisabledUserPluginIds());
		preferencesBean.getEnabledBundledPluginIds().addAll(_preferences.getEnabledBundledPluginIds());
		preferencesBean.getDisabledBundledPluginIds().addAll(_preferences.getDisabledBundledPluginIds());

		preferencesBean.getEnabledModuleConfigs().addAll(_preferences.getEnabledModuleConfigs());

		preferencesBean.setAnnotationSuppressWarningsClass(_preferences.getAnnotationSuppressWarningsClass());
		preferencesBean.setAnnotationGutterIconEnabled(_preferences.isAnnotationGutterIconEnabled());
		preferencesBean.setAnnotationTextRangeMarkupEnabled(_preferences.isAnnotationTextRangeMarkupEnabled());
		preferencesBean.setAnnotationTypeSettings(_preferences.getFlattendAnnotationTypeSettings());


		return preferencesBean;
	}


	@SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE")
	@NotNull
	@Override
	public String getId() {
		return FindBugsPluginConstants.PLUGIN_ID;
	}


	@Override
	public Runnable enableSearch(final String option) {
		return new Runnable() {
			public void run() {
				if (_configPanel != null) {
					_configPanel.setFilter(option);
				}
			}
		};
	}


	private void buildSearchIndexIfNecessary() {
		final SearchableOptionsRegistrar registrar = SearchableOptionsRegistrar.getInstance();
		if (registrar == null) {
			return;
		}
		if (!SEARCH_INDEX_CREATED.getAndSet(true)) {
			for (final Entry<String, String> entry : _preferences.getDetectors().entrySet()) {
				final DetectorFactory factory = FindBugsPreferences.getDetectorFactorCollection().getFactory(entry.getKey());
				if (factory != null) {
					addToSearchIndex(registrar, factory.getShortName()); // eg: FindFieldSelfAssignment
					final Set<BugPattern> patterns = factory.getReportedBugPatterns();
					for (final BugPattern pattern : patterns) {
						addToSearchIndex(registrar, pattern.getType()); // eg: SA_FIELD_SELF_ASSIGNMENT
					}
				}
			}
		}
	}


	private void addToSearchIndex(final SearchableOptionsRegistrar registrar, final String option) {
		registrar.addOption(
				option.toLowerCase(Locale.ENGLISH), // should be lowercase
				null, // path
				null, // hit
				FindBugsPluginConstants.PLUGIN_ID,
				FindBugsPluginConstants.PLUGIN_NAME
		);
	}


	private class PluginLoaderImpl extends AbstractPluginLoaderLegacy {

		protected PluginLoaderImpl() {
			super(false);
		}


		@Override
		protected void seenBundledPlugin(final Plugin plugin) {
			cleanupDetectorStates(plugin);
		}


		@Override
		protected void seenUserPlugin(@NotNull final String pluginUrl, final Plugin plugin) {
			cleanupDetectorStates(plugin);
		}


		private void cleanupDetectorStates(final Plugin plugin) {
			if (_preferences.isPluginDisabled(plugin.getPluginId(), false)) {
				if (!_preferences.isUserPluginEnabled(plugin.getPluginId())) {
					_preferences.setDetectorEnabled(plugin, null);
				}
			}
		}


		@Override
		protected void pluginPermanentlyLoaded(final Plugin plugin, final boolean userPlugin) {
			if (!_preferences.isPluginDisabled(plugin.getPluginId(), userPlugin)) {
				// load default detector configuration if necessary
				if (!_preferences.isPluginConfigured(plugin)) {
					_preferences.setDetectorEnabled(plugin, true);
				}
			}
		}
	}
}
