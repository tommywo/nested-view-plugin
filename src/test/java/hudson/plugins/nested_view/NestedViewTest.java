/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Alan Harder
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.nested_view;


import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequestSettings;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlOption;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.xml.XmlPage;
import hudson.model.AllView;
import hudson.model.Cause.UserCause;
import hudson.model.FreeStyleProject;
import hudson.model.ListView;
import static hudson.model.Result.*;
import hudson.model.View;
import hudson.util.FormValidation;
import static hudson.util.FormValidation.Kind.*;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import java.util.TreeMap;
import org.apache.commons.httpclient.NameValuePair;
import static org.junit.Assert.*;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.FailureBuilder;

import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

/**
 * Test interaction of nested-view plugin with Jenkins core.
 * @author Alan Harder
 */
public class NestedViewTest {
    
    @Rule
    public JenkinsRule rule = new JenkinsRule();
    
    @Test
    public void test() throws Exception {
        rule.createFreeStyleProject("Abcd");
        rule.createFreeStyleProject("Efgh");
        WebClient wc = rule.createWebClient();

        // Create a new nested view
        HtmlForm form = wc.goTo("newView").getFormByName("createItem");
        form.getInputByName("name").setValueAttribute("test-nest");
        form.getInputByValue("hudson.plugins.nested_view.NestedView").setChecked(true);
        rule.submit(form);
        // Add some subviews
        form = wc.goTo("view/test-nest/newView").getFormByName("createItem");
        form.getInputByName("name").setValueAttribute("subview");
        form.getInputByValue("hudson.model.ListView").setChecked(true);
        form = rule.submit(form).getFormByName("viewConfig");
        form.getInputByName("useincluderegex").setChecked(true);
        form.getInputByName("includeRegex").setValueAttribute("E.*");
        rule.submit(form);
        form = wc.goTo("view/test-nest/newView").getFormByName("createItem");
        form.getInputByName("name").setValueAttribute("subnest");
        form.getInputByValue("hudson.plugins.nested_view.NestedView").setChecked(true);
        rule.submit(form);
        form = wc.goTo("view/test-nest/newView").getFormByName("createItem");
        form.getInputByName("name").setValueAttribute("suball");
        form.getInputByValue("hudson.model.AllView").setChecked(true);
        rule.submit(form);
        // Verify links to subviews
        HtmlPage page = wc.goTo("view/test-nest/");
        assertNotNull(page.getAnchorByHref("/jenkins/view/test-nest/view/subview/"));
        assertNotNull(page.getAnchorByHref("/jenkins/view/test-nest/view/subnest/"));
        assertNotNull(page.getAnchorByHref("/jenkins/view/test-nest/view/suball/"));
        // Now set a default subview
        form = wc.goTo("view/test-nest/configure").getFormByName("viewConfig");
        List<HtmlOption> options = form.getSelectByName("defaultView").getOptions();
        assertEquals("", options.get(0).getValueAttribute());
        assertEquals("suball", options.get(1).getValueAttribute());
        assertEquals("subview", options.get(2).getValueAttribute());
        // "None" and 2 views in alphabetical order; subnest should not be in list
        assertEquals(3, options.size());
        options.get(1).setSelected(true);
        rule.submit(form);
        // Verify redirect to default subview
        page = wc.goTo("view/test-nest/");
        assertNotNull(page.getAnchorByHref("job/Efgh/"));
        // Verify link to add a subview for empty nested view
        page = wc.goTo("view/test-nest/view/subnest/");
        assertNotNull(page.getAnchorByHref("/jenkins/view/test-nest/view/subnest/newView"));
    }

    @Test
    public void testGetWorstResult() throws Exception {
        NestedView view = new NestedView("test");
        view.setOwner(rule.jenkins);
        assertSame(null, NestedView.getWorstResult(view));    // Empty
        view.addView(new AllView("foo", view));
        assertSame(null, NestedView.getWorstResult(view));    // Empty
        FreeStyleProject p = rule.createFreeStyleProject();
        assertSame(null, NestedView.getWorstResult(view));    // Job not yet run
        rule.assertBuildStatusSuccess(p.scheduleBuild2(0, new UserCause()).get());
        assertSame(SUCCESS, NestedView.getWorstResult(view));    // Job ran ok
        FreeStyleProject bad = rule.createFreeStyleProject();
        bad.getBuildersList().add(new FailureBuilder());
        assertSame(SUCCESS, NestedView.getWorstResult(view));    // New job not yet run
        rule.assertBuildStatus(FAILURE, bad.scheduleBuild2(0, new UserCause()).get());
        assertSame(FAILURE, NestedView.getWorstResult(view));    // Job failed
        bad.disable();
        assertSame(SUCCESS, NestedView.getWorstResult(view));    // Ignore disabled job
    }

    @Test
    public void testStatusOfEmptyNest() throws Exception {
        NestedView parent = new NestedView("parent");
        parent.setOwner(rule.jenkins);
        NestedView child = new NestedView("child");
        parent.addView(child);
        assertSame(null, NestedView.getWorstResult(child));     // Empty
        assertSame(null, NestedView.getWorstResult(parent));    // contains Empty child only
    }

    @Test
    public void testDoViewExistsCheck() {
        NestedView view = new NestedView("test");
        view.setOwner(rule.jenkins);
        view.addView(new ListView("foo", view));
        assertSame(OK, view.doViewExistsCheck(null).kind);
        assertSame(OK, view.doViewExistsCheck("").kind);
        assertSame(OK, view.doViewExistsCheck("bar").kind);
        assertSame(ERROR, view.doViewExistsCheck("foo").kind);
    }
    
    //nested view should be reloadable from config.xml which it provides
    @Test
    public void testDotConfigXmlOwnerSettings() throws Exception{
        NestedView root = new NestedView("nestedRoot");
        root.setOwner(rule.jenkins);
        ListView viewLevel1 = new ListView("listViewlvl1", root);
        NestedView subviewLevel1 = new NestedView("nestedViewlvl1");
        subviewLevel1.setOwner(root);
        NestedView subviewLevel2 = new NestedView ("nestedViewlvl2");
        subviewLevel2.setOwner(subviewLevel1);
        ListView viewLevel2 = new ListView("listViewlvl2", subviewLevel1);
        ListView viewLevel3 = new ListView("listViewlvl3", subviewLevel2);
        root.addView(viewLevel1);
        root.addView(subviewLevel1);
        subviewLevel1.addView(subviewLevel2);
        subviewLevel1.addView(viewLevel2);
        subviewLevel2.addView(viewLevel3);
        rule.jenkins.addView(root);
        root.save();
        WebClient wc = rule.createWebClient();
        URL url = new URL(rule.jenkins.getRootUrl() + root.getUrl() + "config.xml");
        XmlPage page = wc.getPage(url);
        String configDotXml = page.getWebResponse().getContentAsString();
        configDotXml = configDotXml.replace("listViewlvl1", "new");
        url = new URL(rule.jenkins.getRootUrl() + root.getUrl() + "config.xml/?.crumb=test");
        WebRequestSettings s = new WebRequestSettings(url, HttpMethod.POST);
        s.setRequestBody(configDotXml);
        wc.addRequestHeader("Content-Type", "application/xml");
        HtmlPage p = wc.getPage(s);
        assertEquals("Root Nested view should have set owner.", rule.jenkins, rule.jenkins.getView("nestedRoot").getOwner());
        assertNotNull("Configuration should be updated.", ((NestedView)rule.jenkins.getView("nestedRoot")).getView("new"));
        root = (NestedView) rule.jenkins.getView("nestedRoot");
        assertEquals("Nested subview should have correct owner.", root, root.getView("nestedViewlvl1").getOwner());
        assertEquals("ListView subview should have correct woner.",root, root.getView("new").getOwner());
        NestedView subview = (NestedView) root.getView("nestedViewlvl1");
        assertEquals("Nested subview of subview should have correct woner.",subview, subview.getView("nestedViewlvl2").getOwner());
        assertEquals("Listview subview of subview should have correct woner.",subview, subview.getView("nestedViewlvl2").getOwner());  
    }
}
