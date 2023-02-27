/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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

package org.jboss.set.aphrodite.issue.trackers.jira;

import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.API_ISSUE_PATH;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.BROWSE_ISSUE_PATH;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.FLAG_MAP;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.JSON_CUSTOM_FIELD;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.PROJECTS_ISSUE_PATTERN;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.SECURITY_SENSITIVE;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.SECURITY_SENSITIVE_VALUE_TRUE;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.TARGET_RELEASE;
import static org.jboss.set.aphrodite.issue.trackers.jira.JiraFields.getJiraTransition;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.set.aphrodite.common.Utils;
import org.jboss.set.aphrodite.config.IssueTrackerConfig;
import org.jboss.set.aphrodite.config.TrackerType;
import org.jboss.set.aphrodite.domain.Comment;
import org.jboss.set.aphrodite.domain.Flag;
import org.jboss.set.aphrodite.domain.Issue;
import org.jboss.set.aphrodite.domain.Release;
import org.jboss.set.aphrodite.domain.SearchCriteria;
import org.jboss.set.aphrodite.issue.trackers.common.AbstractIssueTracker;
import org.jboss.set.aphrodite.issue.trackers.common.IssueCreationDetails;
import org.jboss.set.aphrodite.issue.trackers.jira.auth.BearerHttpAuthenticationHandler;
import org.jboss.set.aphrodite.spi.AphroditeException;
import org.jboss.set.aphrodite.spi.NotFoundException;

import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClientFactory;
import com.atlassian.jira.rest.client.api.SearchRestClient;
import com.atlassian.jira.rest.client.api.domain.BasicIssue;
import com.atlassian.jira.rest.client.api.domain.Filter;
import com.atlassian.jira.rest.client.api.domain.Project;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.atlassian.jira.rest.client.api.domain.Version;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.IssueInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import com.atlassian.jira.rest.client.api.domain.input.LinkIssuesInput;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;

import io.atlassian.util.concurrent.Promise;


/**
 * An implementation of the <code>IssueTrackerService</code> for the JIRA issue tracker.
 *
 * @author Ryan Emerson
 */
public class JiraIssueTracker extends AbstractIssueTracker {

    static final Pattern JIRAFIXVERSION = Pattern.compile("(\\d\\.)(\\d\\.)(\\d+).GA");

    private static final Log LOG = LogFactory.getLog(JiraIssueTracker.class);

    private final IssueWrapper WRAPPER = new IssueWrapper();
    private final JiraQueryBuilder queryBuilder = new JiraQueryBuilder();
    private JiraRestClient restClient ;

    public JiraIssueTracker() {
        super(TrackerType.JIRA);
    }

    @Override
    public boolean init(IssueTrackerConfig config) {
        boolean parentInitiated = super.init(config);
        if (!parentInitiated)
            return false;

        try {
            JiraRestClientFactory factory = new AsynchronousJiraRestClientFactory();
            URI jiraServerUri = baseUrl.toURI();
            String username = config.getUsername();
            String password = config.getPassword();
            if (username == null || username.isEmpty()) {
                // We're moving to use PAT for Jira, username is null and password is taken as PAT from configuration.
                restClient= factory.createWithAuthenticationHandler(jiraServerUri, new BearerHttpAuthenticationHandler(password));
            } else {
                // Attempt with username/password for Basic Authentication.
                restClient = factory.createWithBasicHttpAuthentication(jiraServerUri, username, password);
            }
            //work around to auth. No need to check number, its just garbage or general login failure number, not related to our
            //activity.
            restClient.getSessionClient().getCurrentSession().get().getLoginInfo().getFailedLoginCount();
        } catch (Exception e) {
            Utils.logException(LOG, e);
            return false;
        }
        return true;
    }

    @Override
    public Issue getIssue(URL url) throws NotFoundException {
        String issueKey = getIssueKey(url);
        List<IssueRestClient.Expandos> expandos = createExpandos();
        try {
            checkHost(url);
            com.atlassian.jira.rest.client.api.domain.Issue issue = restClient.getIssueClient().getIssue(issueKey, expandos)
                    .get();
            return WRAPPER.jiraIssueToIssue(url, issue);
        } catch (InterruptedException e) {
            throw new NotFoundException("Something interrupted the execution when trying to retrieve issue " + issueKey, e);
        } catch (ExecutionException e) {
            throw new NotFoundException("Unable to retrieve issue with id: " + issueKey , e);
        }

    }

    public List<Issue> getIssues(String project, Version version) {
        List<Issue> issues;
        SearchCriteria sc = new SearchCriteria.Builder()
                .setRelease(new Release(version.getName().trim()))
                .setProduct(project)
                .setMaxResults(config.getDefaultIssueLimit())
                .build();
        issues = searchIssues(sc);
        return issues;
    }

    public List<Issue> getIssuesAddedToVersion(String project, Version version, LocalDate from, LocalDate to) {
        List<Issue> issues;
        SearchCriteria sc = new SearchCriteria.Builder()
                .setRelease(new Release(version.getName().trim()))
                .setProduct(project)
                .setStartDate(from)
                .setEndDate(to)
                .build();
        issues = searchNewIssues(queryBuilder.getSearchJQLFromTo(sc), 20);
        return issues;
    }

    private List<IssueRestClient.Expandos> createExpandos() {
        List<IssueRestClient.Expandos> expandos = new ArrayList<>();
        expandos.add(IssueRestClient.Expandos.CHANGELOG);
        return expandos;
    }

    private com.atlassian.jira.rest.client.api.domain.Issue getIssue(Issue issue) throws NotFoundException {
        String trackerId = issue.getTrackerId().orElse(getIssueKey(issue.getURL()));
        return getIssue(trackerId);
    }

    private com.atlassian.jira.rest.client.api.domain.Issue getIssue(String trackerId) throws NotFoundException {
        try {
            return restClient.getIssueClient().getIssue(trackerId).get();
        } catch (Exception e) {
            throw new NotFoundException(e);
        }
    }

    @Override
    public List<Issue> getIssues(Collection<URL> urls) {
        urls = filterUrlsByHost(urls);
        if (urls.isEmpty())
            return new ArrayList<>();

        List<String> ids = new ArrayList<>();
        for (URL url : urls) {
            try {
                ids.add(getIssueKey(url));
            } catch (NotFoundException e) {
                if (LOG.isWarnEnabled())
                    LOG.warn("Unable to extract trackerId from: " + url);
            }
        }
        String jql = queryBuilder.getMultipleIssueJQL(ids);
        return searchIssues(jql, ids.size());
    }

    @Override
    public List<Issue> searchIssues(SearchCriteria searchCriteria) {
        String jql = queryBuilder.getSearchJQL(searchCriteria);
        int maxResults = searchCriteria.getMaxResults().orElse(config.getDefaultIssueLimit());
        return searchIssues(jql, maxResults);
    }

    private List<Issue> searchIssues(String jql, int maxResults) {
        try {
            Set<String> fields = new HashSet<>();
            fields.add("*all");
            return paginateResults(restClient.getSearchClient(), jql, fields, maxResults);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Issue> searchNewIssues(String jql, int maxResults) {
        try {
            /* minimal amount of required fields */
            Set<String> fields = new HashSet<>();
            fields.add("summary");
            fields.add("issuetype");
            fields.add("created");
            fields.add("updated");
            fields.add("project");
            fields.add("status");
            fields.add("priority");
            fields.add("components");
            return paginateResults(restClient.getSearchClient(), jql, fields, maxResults);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static int NB_TOTAL_ISSUE_NOT_INITIATED = -1;
    private List<Issue> paginateResults(SearchRestClient searchClient, String jql, Set<String> fields, int maxResults) throws InterruptedException, ExecutionException {
        List<Issue> issues = new ArrayList<>();
        int startPosition = 0;
        int nbTotalIssue = NB_TOTAL_ISSUE_NOT_INITIATED;
        if ( LOG.isDebugEnabled() ) LOG.debug("Max Results:" + maxResults);

        do {
            if ( LOG.isDebugEnabled() ) LOG.debug("Start Position:" + startPosition);
            SearchResult result = searchClient.searchJql(jql, maxResults, startPosition, fields).get();
            if ( nbTotalIssue == NB_TOTAL_ISSUE_NOT_INITIATED ) {
                nbTotalIssue = result.getTotal();
                if ( LOG.isDebugEnabled() ) LOG.debug("Total Issues in result:" + nbTotalIssue);
            }
            result.getIssues().forEach(issue -> issues.add(WRAPPER.jiraSearchIssueToIssue(baseUrl, issue)));
            startPosition += maxResults;
        } while ( startPosition < nbTotalIssue );
        if ( LOG.isDebugEnabled() ) LOG.debug("Total issues:" + issues.size());
        return issues;
    }

    @Override
    public List<Issue> searchIssuesByFilter(URL filterUrl) throws NotFoundException {
        String jql = getJQLFromFilter(filterUrl);
        return searchIssues(jql, config.getDefaultIssueLimit());
    }

    private String getJQLFromFilter(URL filterUrl) throws NotFoundException {
        try {
            // url type example https://issues.redhat.com/rest/api/latest/filter/12322199
            SearchRestClient searchClient = restClient.getSearchClient();
            Filter filter = searchClient.getFilter(filterUrl.toURI()).get();
            return filter.getJql();
        } catch (Exception e) {
            throw new NotFoundException("Unable to retrieve filter with url: " + filterUrl, e);
        }
    }

    /**
     * Known limitations:
     * - Jira api does not allow an issue type to be update (WTF?)
     * - Jira api does not allow project to be changed
     */
    @Override
    public boolean updateIssue(Issue issue) throws NotFoundException, AphroditeException {
        try {
            checkHost(issue.getURL());

            com.atlassian.jira.rest.client.api.domain.Issue jiraIssue = getIssue(issue);
            Project project = restClient.getProjectClient().getProject(jiraIssue.getProject().getSelf()).claim();
            IssueInput update = WRAPPER.issueToFluentUpdate(issue, jiraIssue, project);

            IssueRestClient issueClient = restClient.getIssueClient();
            issueClient.updateIssue(jiraIssue.getKey(), update).claim();
            if (!JiraFields.hasSameIssueStatus(issue, jiraIssue)) {
                String transition = getJiraTransition(issue, jiraIssue);
                for(Transition t : issueClient.getTransitions(jiraIssue).get()) {
                    if(t.getName().equals(transition)) {
                        issueClient.transition(jiraIssue, new TransitionInput(t.getId())).claim();
                    }
                }
            }

            return true;
        } catch (ExecutionException | InterruptedException e) {
            throw new AphroditeException(getUpdateErrorMessage(issue, e), e);
        }
    }

    private String toKey(URL url) {
        try {
            return getIssueKey(url);
        } catch (NotFoundException e) {
            return "";
        }
    }

    @Override
    public void addCommentToIssue(Issue issue, Comment comment) throws NotFoundException {
        super.addCommentToIssue(issue, comment);
        postComment(issue, comment);
    }

    private void postComment(Issue issue, Comment comment) throws NotFoundException {
        if (comment.isPrivate())
            Utils.logWarnMessage(LOG, "Private comments are not currently supported by " + getClass().getName());
        com.atlassian.jira.rest.client.api.domain.Issue jiraIssue = getIssue(issue);

        com.atlassian.jira.rest.client.api.domain.Comment c =
                com.atlassian.jira.rest.client.api.domain.Comment.valueOf(comment.getBody());
        restClient.getIssueClient().addComment(jiraIssue.getCommentsUri(), c).claim();
    }

    @Override
    public boolean addCommentToIssue(Map<Issue, Comment> commentMap) {
        commentMap = filterIssuesByHost(commentMap);
        List<CompletableFuture<Boolean>> requests = commentMap.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(
                        () -> postCommentAndLogExceptions(entry.getKey(), entry.getValue()), executorService))
                .collect(Collectors.toList());

        return requests.stream()
                .map(CompletableFuture::join)
                .noneMatch(failed -> !failed);
    }

    @Override
    public boolean addCommentToIssue(Collection<Issue> issues, Comment comment) {
        issues = filterIssuesByHost(issues);

        List<CompletableFuture<Boolean>> requests = issues.stream()
                .map(issue -> CompletableFuture.supplyAsync(
                        () -> postCommentAndLogExceptions(issue, comment), executorService))
                .collect(Collectors.toList());

        return requests.stream()
                .map(CompletableFuture::join)
                .noneMatch(failed -> !failed);
    }

    private boolean postCommentAndLogExceptions(Issue issue, Comment comment) {
        try {
            postComment(issue, comment);
            return true;
        } catch (NotFoundException e) {
            Utils.logException(LOG, e);
            return false;
        }
    }

    @Override
    public Log getLog() {
        return LOG;
    }

    static String getIssueKey(URL url) throws NotFoundException {
        String path = correctPath(url.getPath());
        boolean api = path.contains(API_ISSUE_PATH);
        boolean browse = path.contains(BROWSE_ISSUE_PATH);

        if (!(api || browse))
            throw new NotFoundException("The URL path must be of the form '" + API_ISSUE_PATH + "' OR '" + BROWSE_ISSUE_PATH + "'");

        return api ? path.substring(API_ISSUE_PATH.length()) : path.substring(BROWSE_ISSUE_PATH.length());
    }

    static String correctPath(String path) {
        Matcher m = PROJECTS_ISSUE_PATTERN.matcher(path);
        if (m.find()) {
            return m.replaceFirst(BROWSE_ISSUE_PATH);
        }
        return path;
    }

    private String getUpdateErrorMessage(Issue issue, Exception e) {
        String msg = e.getMessage();
        if (msg.contains("does not exist or read-only")) {
            for (Map.Entry<Flag, String> entry : FLAG_MAP.entrySet()) {
                if (msg.contains(entry.getValue())) {
                    String retMsg = "Flag '%1$s' set in Issue.stage cannot be set for %2$s '%3$s'";
                    return getOptionalErrorMessage(retMsg, issue.getProduct(), entry.getKey(), issue.getURL());
                }
            }
            if (msg.contains(TARGET_RELEASE)) {
                String retMsg = "Release.milestone cannot be set for %2$s ''%3$s'";
                return getOptionalErrorMessage(retMsg, issue.getProduct(), null, issue.getURL());
            }
        }
        return null;
    }

    private String getOptionalErrorMessage(String template, Optional<?> optional, Object val, URL url) {
        if (optional.isPresent())
            return String.format(template, val, "issues in project", optional.get());
        else
            return String.format(template, val, "issue at ", url);
    }

    @Override
    public void destroy() {
        try {
            restClient.close();
        } catch (IOException e) {
            LOG.warn("destroyin jira issue tracker", e);
        }
    }

    @Override
    public boolean isCPReleased(String cpVersion) {
        // For Jira, only accept GA version format x.y.z.GA, e.g. 7.1.2.GA
        // ignore CR version like 7.0.7.CR3
        Matcher matcher = JIRAFIXVERSION.matcher(cpVersion);
        if (!matcher.matches()) {
            return false;
        }
        Promise<Project> promise = restClient.getProjectClient().getProject("JBEAP");
        Project project = promise.claim();

        Optional<Version> version = StreamSupport.stream(project.getVersions().spliterator(), false)
                                                 .filter(v -> v.getName().equals(cpVersion))
                                                 .findAny();
        if (version.isPresent()) {
            return version.get().isReleased();
        }

        return false;
    }

    public Iterable<Version> getVersionsByProject(String projectName) {
        return restClient.getProjectClient().getProject(projectName).claim().getVersions();
    }

    @Override
    public Issue createIssue(final IssueCreationDetails details) throws MalformedURLException, NotFoundException, AphroditeException {

        assert details != null;
        assert details instanceof JIRAIssueCreationDetails;

        final JIRAIssueCreationDetails localDetails = (JIRAIssueCreationDetails) details;

        assert details.getTrackerURL() != null;
        assert details.getProjectKey() != null;
        assert details.getDescription() != null;
        assert localDetails.getIssueType() != null;

        final IssueInputBuilder builder = new IssueInputBuilder(localDetails.getProjectKey(), localDetails.getIssueType(), localDetails.getDescription());
        if (localDetails.isSecuritySensitiveIssue()) {
            builder.setFieldValue(JSON_CUSTOM_FIELD + SECURITY_SENSITIVE,
                    Arrays.asList(ComplexIssueInputFieldValue.with("id", SECURITY_SENSITIVE_VALUE_TRUE)));
        }
        if (localDetails.getSecurityLevel() != null) {
            String id = JiraFields.getSecurityLevelId(localDetails.getSecurityLevel());
            builder.setFieldValue("security", ComplexIssueInputFieldValue.with("id", id));
        }

        final IssueInput newIssue = builder.build();
        final BasicIssue basicIssue = restClient.getIssueClient().createIssue(newIssue).claim();

        final URL trackerURL = localDetails.getTrackerURL();

        // org.jboss.set.aphrodite.domain.Issue issue = tracker.getIssue(new URL(basicIssue.getKey()));
        if (trackerURL.toString().endsWith("browse") || trackerURL.toString().endsWith("browse/")) {
            return this.getIssue(new URL(trackerURL, basicIssue.getKey()));
        } else {
            return this.getIssue(new URL(trackerURL, "browse/" + basicIssue.getKey()));
        }
    }

    public void linkIssues(Issue from, Issue to, String linkType) {
        LinkIssuesInput link = new LinkIssuesInput(toKey(from.getURL()), toKey(to.getURL()),linkType);
        restClient.getIssueClient().linkIssue(link).claim();
    }
}
