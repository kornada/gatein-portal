/**
 * Copyright (C) 2009 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.exoplatform.portal.pom.config;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;

import javax.transaction.Status;

import org.chromattic.api.ChromatticSession;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.commons.utils.LazyPageList;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.configuration.ConfigurationManager;
import org.exoplatform.portal.application.PortletPreferences;
import org.exoplatform.portal.config.NoSuchDataException;
import org.exoplatform.portal.config.Query;
import org.exoplatform.portal.config.model.Application;
import org.exoplatform.portal.config.model.ApplicationState;
import org.exoplatform.portal.config.model.ApplicationType;
import org.exoplatform.portal.config.model.CloneApplicationState;
import org.exoplatform.portal.config.model.Container;
import org.exoplatform.portal.config.model.ModelObject;
import org.exoplatform.portal.config.model.PersistentApplicationState;
import org.exoplatform.portal.config.model.PortalConfig;
import org.exoplatform.portal.config.model.TransientApplicationState;
import org.exoplatform.portal.mop.EventType;
import org.exoplatform.portal.mop.SiteKey;
import org.exoplatform.portal.mop.hierarchy.NodeContext;
import org.exoplatform.portal.mop.layout.ElementState;
import org.exoplatform.portal.mop.layout.LayoutService;
import org.exoplatform.portal.mop.layout.LayoutServiceImpl;
import org.exoplatform.portal.mop.page.PageContext;
import org.exoplatform.portal.mop.page.PageService;
import org.exoplatform.portal.mop.page.PageState;
import org.exoplatform.portal.pom.config.tasks.DashboardTask;
import org.exoplatform.portal.pom.config.tasks.PortalConfigTask;
import org.exoplatform.portal.pom.config.tasks.PreferencesTask;
import org.exoplatform.portal.pom.config.tasks.SearchTask;
import org.exoplatform.portal.pom.data.ApplicationData;
import org.exoplatform.portal.pom.data.BodyData;
import org.exoplatform.portal.pom.data.ComponentData;
import org.exoplatform.portal.pom.data.ContainerData;
import org.exoplatform.portal.pom.data.DashboardData;
import org.exoplatform.portal.pom.data.Mapper;
import org.exoplatform.portal.pom.data.ModelChange;
import org.exoplatform.portal.pom.data.ModelData;
import org.exoplatform.portal.pom.data.ModelDataStorage;
import org.exoplatform.portal.pom.data.PageData;
import org.exoplatform.portal.pom.data.PageKey;
import org.exoplatform.portal.pom.data.PortalData;
import org.exoplatform.portal.pom.data.PortalKey;
import org.exoplatform.portal.tree.diff.HierarchyAdapter;
import org.exoplatform.portal.tree.diff.HierarchyChangeIterator;
import org.exoplatform.portal.tree.diff.HierarchyChangeType;
import org.exoplatform.portal.tree.diff.HierarchyDiff;
import org.exoplatform.portal.tree.diff.ListAdapter;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.listener.ListenerService;
import org.gatein.common.logging.Logger;
import org.gatein.common.logging.LoggerFactory;
import org.gatein.common.transaction.JTAUserTransactionLifecycleService;
import org.gatein.mop.api.workspace.ObjectType;
import org.gatein.mop.api.workspace.Site;
import org.gatein.mop.api.workspace.WorkspaceObject;
import org.gatein.mop.api.workspace.ui.UIComponent;
import org.gatein.mop.api.workspace.ui.UIWindow;
import org.jibx.runtime.BindingDirectory;
import org.jibx.runtime.IBindingFactory;
import org.jibx.runtime.impl.UnmarshallingContext;

/**
 * @author <a href="mailto:julien.viet@exoplatform.com">Julien Viet</a>
 * @version $Revision$
 */
public class POMDataStorage implements ModelDataStorage {

    private static final Logger log = LoggerFactory.getLogger(POMDataStorage.class);

    /** . */
    private final POMSessionManager pomMgr;

    /** . */
    private ConfigurationManager confManager_;

    /** . */
    private JTAUserTransactionLifecycleService jtaUserTransactionLifecycleService;

    /** . */
    private final ListenerService listenerService;

    public POMDataStorage(final POMSessionManager pomMgr, ConfigurationManager confManager,
            JTAUserTransactionLifecycleService jtaUserTransactionLifecycleService, ListenerService listenerService) {

        // Invalidation bridge : listen for PageService events and invalidate the DataStorage cache
        Listener<?, org.exoplatform.portal.mop.page.PageKey> invalidator = new Listener<Object, org.exoplatform.portal.mop.page.PageKey>() {
            @Override
            public void onEvent(Event<Object, org.exoplatform.portal.mop.page.PageKey> event) throws Exception {
                org.exoplatform.portal.mop.page.PageKey key = event.getData();
                PageKey adaptedKey = new PageKey(key.getSite().getTypeName(), key.getSite().getName(), key.getName());
                pomMgr.getSession().scheduleForEviction(adaptedKey);
            }
        };
        listenerService.addListener(EventType.PAGE_UPDATED, invalidator);
        listenerService.addListener(EventType.PAGE_DESTROYED, invalidator);

        //
        this.pomMgr = pomMgr;
        this.confManager_ = confManager;
        this.jtaUserTransactionLifecycleService = jtaUserTransactionLifecycleService;
        this.listenerService = listenerService;
    }

    public PortalData getPortalConfig(PortalKey key) throws Exception {
        return pomMgr.execute(new PortalConfigTask.Load(key));
    }

    public void create(PortalData config) throws Exception {
        pomMgr.execute(new PortalConfigTask.Save(config, false));
    }

    public void save(PortalData config) throws Exception {
        pomMgr.execute(new PortalConfigTask.Save(config, true));
    }

    public void remove(PortalData config) throws Exception {
        pomMgr.execute(new PortalConfigTask.Remove(config.getKey()));
    }

    public PageData getPage(PageKey key) throws Exception {
        PageService pageService = (PageService) PortalContainer.getComponent(PageService.class);
        PageContext context = pageService.loadPage(new org.exoplatform.portal.mop.page.PageKey(
                new SiteKey(key.getType(), key.getId()),
                key.getName()
        ));
        if (context != null) {
            LayoutService layoutService = new LayoutServiceImpl(pomMgr);
            NodeContext<?, ElementState> ret = layoutService.loadElement(ElementState.model(), context.getLayoutId(), null);
            ContainerData container = (ContainerData)create(ret);
            return new PageData(
                    context.getLayoutId(),
                    null,
                    key.getName(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Collections.<String> emptyList(),
                    container.getChildren(),
                    key.getType(),
                    key.getId(),
                    null,
                    false);
        } else {
            return null;
        }
    }

    private ComponentData create(NodeContext<?, ElementState> context) {
        ElementState state = context.getState();
        if (state instanceof ElementState.Container) {
            ElementState.Container container = (ElementState.Container) state;
            ArrayList<ComponentData> children = new ArrayList<ComponentData>();
            for (NodeContext<?, ElementState> child : context) {
                children.add(create(child));
            }
            return new ContainerData(
                    context.getId(),
                    container.id,
                    container.name,
                    container.icon,
                    container.template,
                    container.factoryId,
                    container.title,
                    container.description,
                    container.width,
                    container.height,
                    container.accessPermissions,
                    children
            );
        } else if (state instanceof ElementState.Window) {
            ElementState.Window window = (ElementState.Window) state;
            return new ApplicationData(
                    context.getId(),
                    context.getName(),
                    window.type,
                    window.state,
                    null,
                    window.title,
                    window.icon,
                    window.description,
                    window.showInfoBar,
                    window.showApplicationState,
                    window.showApplicationMode,
                    window.theme,
                    window.width,
                    window.height,
                    window.properties,
                    window.accessPermissions
            );
        } else  {
            throw new UnsupportedOperationException("todo : " + state.getClass().getName());
        }
    }

    public List<ModelChange> save(PageData page) throws Exception {

        //
        class PageHierarchyAdapter implements HierarchyAdapter<List<ComponentData>, ComponentData, String>, ListAdapter<List<ComponentData>, String> {

            /** . */
            final ContainerData page;

            final IdentityHashMap<ComponentData, String> handles = new IdentityHashMap<ComponentData, String>();

            PageHierarchyAdapter(ContainerData page) {
                this.page = page;
            }

            @Override
            public String getHandle(ComponentData node) {
                String handle = node.getStorageId();
                if (handle == null) {
                    handle = handles.get(node);
                    if (handle == null) {
                        handles.put(node, handle = UUID.randomUUID().toString());
                    }
                }
                return handle;
            }
            @Override
            public List<ComponentData> getChildren(ComponentData node) {
                if (node instanceof ContainerData) {
                    return ((ContainerData)node).getChildren();
                } else {
                    return Collections.emptyList();
                }
            }
            @Override
            public ComponentData getDescendant(ComponentData node, String handle) {
                String h = getHandle(node);
                if (h.equals(handle)) {
                    return node;
                } else if (node instanceof ContainerData) {
                    ContainerData container = (ContainerData) node;
                    for (ComponentData child : container.getChildren()) {
                        ComponentData descendant = getDescendant(child, handle);
                        if (descendant != null) {
                            return descendant;
                        }
                    }
                    return null;
                } else {
                    return null;
                }
            }
            @Override
            public int size(List<ComponentData> list) {
                return list.size();
            }
            @Override
            public Iterator<String> iterator(List<ComponentData> list, boolean reverse) {
                ArrayList<String> ret = new ArrayList<String>();
                for (ComponentData c : list) {
                    ret.add(getHandle(c));
                }
                if (reverse) {
                    Collections.reverse(ret);
                }
                return ret.iterator();
            }
            public ContainerData getParent(ComponentData node) {
                return getParent(page, node);
            }
            private ContainerData getParent(ContainerData container, ComponentData node) {
                for (ComponentData child : container.getChildren()) {
                    if (child == node) {
                        return container;
                    } else if (child instanceof ContainerData) {
                        ContainerData parent = getParent((ContainerData) child, node);
                        if (parent != null) {
                            return parent;
                        }
                    }
                }
                return null;
            }
        }

        Comparator<String> c = new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return o1.compareTo(o2);
            }
        };

        class NodeContextHierarchyAdapter implements HierarchyAdapter<List<String>, NodeContext<?, ElementState>, String>, ListAdapter<List<String>, String> {

            @Override
            public String getHandle(NodeContext<?, ElementState> node) {
                return node.getId();
            }

            @Override
            public List<String> getChildren(NodeContext<?, ElementState> node) {
                ArrayList<String> ret = new ArrayList<String>(node.getSize());
                for (NodeContext<?, ElementState> child : node) {
                    ret.add(child.getId());
                }
                return ret;
            }

            @Override
            public NodeContext<?, ElementState> getDescendant(NodeContext<?, ElementState> node, String handle) {
                return node.getDescendant(handle);
            }

            @Override
            public int size(List<String> list) {
                return list.size();
            }

            @Override
            public Iterator<String> iterator(List<String> list, boolean reverse) {
                if (reverse) {
                    final ListIterator<String> i = list.listIterator(list.size());
                    return new Iterator<String>() {
                        @Override
                        public boolean hasNext() {
                            return i.hasPrevious();
                        }
                        @Override
                        public String next() {
                            return i.previous();
                        }
                        @Override
                        public void remove() {
                            throw new UnsupportedOperationException();
                        }
                    };
                } else {
                    return list.iterator();
                }
            }
        }

        // Build layout context
        PageService pageService = (PageService) PortalContainer.getComponent(PageService.class);
        org.exoplatform.portal.mop.page.PageKey key = new org.exoplatform.portal.mop.page.PageKey(new SiteKey(page.getKey().getType(), page.getKey().getId()), page.getKey().getName());
        PageContext context = pageService.loadPage(key);

        //
        if (context == null) {
            context = new PageContext(key, new PageState(
                    page.getName(),
                    page.getDescription(),
                    page.isShowMaxWindow(),
                    page.getFactoryId(),
                    page.getAccessPermissions(),
                    page.getEditPermission()
            ));
            pageService.savePage(context);
        }

        //
        LayoutService layoutService = new LayoutServiceImpl(pomMgr);
        NodeContext<?, ElementState> ret = layoutService.loadElement(ElementState.model(), context.getLayoutId(), null);

        // Need to use the context ID
        page = new PageData(
                ret.getId(),
                page.getId(),
                page.getName(),
                page.getIcon(),
                page.getTemplate(),
                page.getFactoryId(),
                page.getTitle(),
                page.getDescription(),
                page.getWidth(),
                page.getHeight(),
                page.getAccessPermissions(),
                page.getChildren(),
                page.getOwnerType(),
                page.getOwnerId(),
                page.getEditPermission(),
                false
        );

        //
        ContainerData container = (ContainerData) fix(page);

        //
        PageHierarchyAdapter context1 = new PageHierarchyAdapter(container);
        NodeContextHierarchyAdapter context2 = new NodeContextHierarchyAdapter();
        HierarchyDiff<List<String>, NodeContext<?, ElementState>, List<ComponentData>, ComponentData, String> diff = HierarchyDiff.create(
                context2,
                context2,
                context1,
                context1,
                c
        );

        //
        HierarchyChangeIterator<List<String>, NodeContext<?, ElementState>, List<ComponentData>, ComponentData, String> i = diff.iterator(ret, container);
        LinkedList<NodeContext<?, ElementState>> previousStack = new LinkedList<NodeContext<?, ElementState>>();
        LinkedList<NodeContext<?, ElementState>> parentStack = new LinkedList<NodeContext<?, ElementState>>();
        while (i.hasNext()) {
            HierarchyChangeType type = i.next();
            switch (type) {
                case ADDED: {
                    ElementState state = create(i.getDestination());
                    NodeContext<?, ElementState> parent = parentStack.peekLast();
                    NodeContext<?, ElementState> previous = previousStack.peekLast();
                    NodeContext<?, ElementState> added;
                    String name = UUID.randomUUID().toString();
                    if (previous != null) {
                        added = parent.add(previous.getIndex() + 1, name, state);
                    } else {
                        added = parent.add(0, name, state);
                    }
                    context1.handles.put(i.getDestination(), added.getHandle());
                    previousStack.set(previousStack.size() - 1, added);
                    break;
                }
                case REMOVED:
                    i.getSource().removeNode();
                    break;
                case MOVED_OUT:
                    break;
                case MOVED_IN: {
                    NodeContext moved = i.getSource();
                    ComponentData cd = i.getDestination();
                    ContainerData parent = context1.getParent(cd);
                    String handle = context1.getHandle(parent);
                    NodeContext<?, ElementState> parent2 = ret.getDescendant(handle);
                    int index = parent.getChildren().indexOf(cd);
                    if (index > 0) {
                        ComponentData pre = index > 0 ? parent.getChildren().get(index - 1) : null;
                        String preHandle = context1.getHandle(pre);
                        NodeContext foo = ret.getDescendant(preHandle);
                        parent2.add(foo.getIndex() + 1, moved);
                    } else {
                        parent2.add(0, moved);
                    }
                    previousStack.set(previousStack.size() - 1, moved);
                    break;
                }
                case KEEP:
                    i.getSource().setState(create(i.getDestination()));
                    previousStack.set(previousStack.size() - 1, i.getSource());
                    break;
                case ENTER:
                    NodeContext<?, ElementState> parent = i.getSource();
                    if (parent == null) {
                        // This is a trick : if the parent is null -> a node was added
                        // and this node should/must be the previous node
                        parentStack.addLast(previousStack.peekLast());
                    } else {
                        parentStack.addLast(parent);
                    }
                    previousStack.addLast(null);
                    break;
                case LEAVE:
                    parentStack.removeLast();
                    previousStack.removeLast();
                    break;
            }
        }

        // Save element
        layoutService.saveElement(ret, null);

        //
        return Collections.emptyList();
    }

    private ComponentData fix(ComponentData component) {
        if (component instanceof PageData) {
            PageData page = (PageData) component;
            return new ContainerData(
                    page.getStorageId(),
                    page.getId(),
                    page.getName(),
                    page.getIcon(),
                    page.getTemplate(),
                    page.getFactoryId(),
                    page.getTitle(),
                    page.getDescription(),
                    page.getWidth(),
                    page.getHeight(),
                    page.getAccessPermissions(),
                    page.getChildren()
            );
        } else {
            throw new UnsupportedOperationException();
        }
    }

    private ElementState create(ComponentData data) {
        if (data instanceof ApplicationData) {
            ApplicationData application = (ApplicationData) data;
            return new ElementState.Window(
                    application.getType(),
                    application.getState(),
                    application.getTitle(),
                    application.getIcon(),
                    application.getDescription(),
                    application.isShowInfoBar(),
                    application.isShowApplicationState(),
                    application.isShowApplicationMode(),
                    application.getTheme(),
                    application.getWidth(),
                    application.getHeight(),
                    application.getProperties(),
                    application.getAccessPermissions()
            );
        } else if (data instanceof BodyData) {
            return new ElementState.Body();
        } else if (data instanceof ContainerData) {
            ContainerData container = (ContainerData) data;
            return new ElementState.Container(
                    container.getId(),
                    container.getName(),
                    container.getIcon(),
                    container.getTemplate(),
                    container.getFactoryId(),
                    container.getTitle(),
                    container.getDescription(),
                    container.getWidth(),
                    container.getHeight(),
                    container.getAccessPermissions(),
                    container instanceof DashboardData
            );
        } else {
            throw new UnsupportedOperationException();
        }
    }


    public <S> String getId(ApplicationState<S> state) throws Exception {
        String contentId;
        if (state instanceof TransientApplicationState) {
            TransientApplicationState tstate = (TransientApplicationState) state;
            contentId = tstate.getContentId();
        } else if (state instanceof PersistentApplicationState) {
            PersistentApplicationState pstate = (PersistentApplicationState) state;
            contentId = pomMgr.execute(new PreferencesTask.GetContentId<S>(pstate.getStorageId()));
        } else if (state instanceof CloneApplicationState) {
            CloneApplicationState cstate = (CloneApplicationState) state;
            contentId = pomMgr.execute(new PreferencesTask.GetContentId<S>(cstate.getStorageId()));
        } else {
            throw new AssertionError();
        }

        //
        return contentId;
    }

    public <S> S load(ApplicationState<S> state, ApplicationType<S> type) throws Exception {
        Class<S> clazz = type.getContentType().getStateClass();
        if (state instanceof TransientApplicationState) {
            TransientApplicationState<S> transientState = (TransientApplicationState<S>) state;
            S prefs = transientState.getContentState();
            return prefs != null ? prefs : null;
        } else if (state instanceof CloneApplicationState) {
            PreferencesTask.Load<S> load = new PreferencesTask.Load<S>(((CloneApplicationState<S>) state).getStorageId(), clazz);
            return pomMgr.execute(load);
        } else {
            PreferencesTask.Load<S> load = new PreferencesTask.Load<S>(((PersistentApplicationState<S>) state).getStorageId(),
                    clazz);
            return pomMgr.execute(load);
        }
    }

    public <S> ApplicationState<S> save(ApplicationState<S> state, S preferences) throws Exception {
        if (state instanceof TransientApplicationState) {
            throw new AssertionError("Does not make sense");
        } else {
            if (state instanceof PersistentApplicationState) {
                PreferencesTask.Save<S> save = new PreferencesTask.Save<S>(
                        ((PersistentApplicationState<S>) state).getStorageId(), preferences);
                pomMgr.execute(save);
            } else {
                PreferencesTask.Save<S> save = new PreferencesTask.Save<S>(((CloneApplicationState<S>) state).getStorageId(),
                        preferences);
                pomMgr.execute(save);
            }
            return state;
        }
    }

    public <T> LazyPageList<T> find(Query<T> q) throws Exception {
        return find(q, null);
    }

    public <T> LazyPageList<T> find(Query<T> q, Comparator<T> sortComparator) throws Exception {
        Class<T> type = q.getClassType();
        if (PageData.class.equals(type)) {
            throw new UnsupportedOperationException("Use PageService.findPages to instead of");
        } else if (PortletPreferences.class.equals(type)) {
            return (LazyPageList<T>) pomMgr.execute(new SearchTask.FindPortletPreferences((Query<PortletPreferences>) q));
        } else if (PortalData.class.equals(type)) {
            return (LazyPageList<T>) pomMgr.execute(new SearchTask.FindSite((Query<PortalData>) q));
        } else if (PortalKey.class.equals(type) && "portal".equals(q.getOwnerType())) {
            return (LazyPageList<T>) pomMgr.execute(new SearchTask.FindSiteKey((Query<PortalKey>) q));
        } else if (PortalKey.class.equals(type) && "group".equals(q.getOwnerType())) {
            return (LazyPageList<T>) pomMgr.execute(new SearchTask.FindSiteKey((Query<PortalKey>) q));
        } else {
            throw new UnsupportedOperationException("Could not perform search on query " + q);
        }
    }

    /**
     * This is a hack and should be removed, it is only used temporarily. This is because the objects are loaded from files and
     * don't have name.
     */
    private void generateStorageName(ModelObject obj) {
        if (obj instanceof Container) {
            for (ModelObject child : ((Container) obj).getChildren()) {
                generateStorageName(child);
            }
        } else if (obj instanceof Application) {
            obj.setStorageName(UUID.randomUUID().toString());
        }
    }

    public DashboardData loadDashboard(String dashboardId) throws Exception {
        return pomMgr.execute(new DashboardTask.Load(dashboardId));
    }

    public void saveDashboard(DashboardData dashboard) throws Exception {
        pomMgr.execute(new DashboardTask.Save(dashboard));
    }

    public Container getSharedLayout() throws Exception {
        String path = "war:/conf/portal/portal/sharedlayout.xml";
        String out = IOUtil.getStreamContentAsString(confManager_.getInputStream(path));
        ByteArrayInputStream is = new ByteArrayInputStream(out.getBytes("UTF-8"));
        IBindingFactory bfact = BindingDirectory.getFactory(Container.class);
        UnmarshallingContext uctx = (UnmarshallingContext) bfact.createUnmarshallingContext();
        uctx.setDocument(is, null, "UTF-8", false);
        Container container = (Container) uctx.unmarshalElement();
        generateStorageName(container);
        return container;
    }

    public void save() throws Exception {
        pomMgr.execute(new POMTask<Object>() {
            public Object run(POMSession session) {
                session.save();
                return null;
            }
        });
    }

    public <A> A adapt(ModelData modelData, Class<A> type) {
        return adapt(modelData, type, true);
    }

    public <A> A adapt(ModelData modelData, Class<A> type, boolean create) {
        try {
            POMSession pomSession = pomMgr.getSession();
            ChromatticSession chromSession = pomSession.getSession();

            // TODO: Deal with the case where modelData is not persisted before invocation to adapt
            // Get the workspace object
            Object o = pomSession.findObjectById(modelData.getStorageId());

            A a = chromSession.getEmbedded(o, type);
            if (a == null && create) {
                a = chromSession.create(type);
                chromSession.setEmbedded(o, type, a);
            }

            return a;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * If we are in JTA environment and there are pending changes in MOP, we will commit current JTA transaction and start new.
     * This will enforce that query result will contain latest persistent stuff
     */
    private void syncUserTransactionIfJTAEnabled() {
        try {
            if (jtaUserTransactionLifecycleService.getUserTransaction().getStatus() == Status.STATUS_ACTIVE) {
                POMSession pomSession = pomMgr.getSession();
                if (pomSession.isModified()) {
                    if (log.isTraceEnabled()) {
                        log.trace("Active JTA transaction found. Going to sync MOP session and JTA transaction");
                    }

                    // Sync current MOP session first
                    pomSession.save();

                    jtaUserTransactionLifecycleService.finishJTATransaction();
                    jtaUserTransactionLifecycleService.beginJTATransaction();
                }
            }
        } catch (Exception e) {
            log.warn("Error during sync of JTA transaction", e);
        }
    }

    public String[] getSiteInfo(String workspaceObjectId) throws Exception {

        POMSession session = pomMgr.getSession();

        WorkspaceObject workspaceObject = session.findObjectById(workspaceObjectId);

        if (workspaceObject instanceof UIComponent) {
            Site site = ((UIComponent) workspaceObject).getPage().getSite();
            ObjectType<? extends Site> siteType = site.getObjectType();

            String[] siteInfo = new String[2];

            // Put the siteType on returned map
            if (siteType == ObjectType.PORTAL_SITE) {
                siteInfo[0] = PortalConfig.PORTAL_TYPE;
            } else if (siteType == ObjectType.GROUP_SITE) {
                siteInfo[0] = PortalConfig.GROUP_TYPE;
            } else if (siteType == ObjectType.USER_SITE) {
                siteInfo[0] = PortalConfig.USER_TYPE;
            }

            // Put the siteOwner on returned map
            siteInfo[1] = site.getName();

            return siteInfo;
        }

        throw new Exception("The provided ID is not associated with an application");
    }

    public <S> ApplicationData<S> getApplicationData(String applicationStorageId) {
        // TODO Auto-generated method stub

        POMSession session = pomMgr.getSession();
        WorkspaceObject workspaceObject = session.findObjectById(applicationStorageId);

        if (workspaceObject instanceof UIWindow) {
            UIWindow application = (UIWindow) workspaceObject;
            Mapper mapper = new Mapper(session);

            ApplicationData data = mapper.load(application);
            return data;
        }
        throw new NoSuchDataException("Could not load the application data specified by the ID: " + applicationStorageId);
    }
}
