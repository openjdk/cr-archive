/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;
import org.openjdk.skara.issuetracker.IssueProject;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.Issue;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.*;

class CheckRun {
    private final CheckWorkItem workItem;
    private final PullRequest pr;
    private final Repository localRepo;
    private final List<Comment> comments;
    private final List<Review> allReviews;
    private final List<Review> activeReviews;
    private final Set<String> labels;
    private final CensusInstance censusInstance;
    private final boolean ignoreStaleReviews;

    private final Hash baseHash;
    private final CheckablePullRequest checkablePullRequest;

    private static final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    private static final String progressMarker = "<!-- Anything below this marker will be automatically updated, please do not edit manually! -->";
    private static final String mergeReadyMarker = "<!-- PullRequestBot merge is ready comment -->";
    private static final String outdatedHelpMarker = "<!-- PullRequestBot outdated help comment -->";
    private static final String sourceBranchWarningMarker = "<!-- PullRequestBot source branch warning comment -->";
    private static final String mergeCommitWarningMarker = "<!-- PullRequestBot merge commit warning comment -->";
    private static final String emptyPrBodyMarker = "<!--\nReplace this text with a description of your pull request (also remove the surrounding HTML comment markers).\n" +
            "If in doubt, feel free to delete everything in this edit box first, the bot will restore the progress section as needed.\n-->";
    private final Set<String> newLabels;
    static final Pattern ISSUE_ID_PATTERN = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9]+-)?([0-9]+)$");

    private CheckRun(CheckWorkItem workItem, PullRequest pr, Repository localRepo, List<Comment> comments,
                     List<Review> allReviews, List<Review> activeReviews, Set<String> labels,
                     CensusInstance censusInstance, boolean ignoreStaleReviews) throws IOException {
        this.workItem = workItem;
        this.pr = pr;
        this.localRepo = localRepo;
        this.comments = comments;
        this.allReviews = allReviews;
        this.activeReviews = activeReviews;
        this.labels = new HashSet<>(labels);
        this.newLabels = new HashSet<>(labels);
        this.censusInstance = censusInstance;
        this.ignoreStaleReviews = ignoreStaleReviews;

        baseHash = PullRequestUtils.baseHash(pr, localRepo);
        checkablePullRequest = new CheckablePullRequest(pr, localRepo, ignoreStaleReviews);
    }

    static void execute(CheckWorkItem workItem, PullRequest pr, Repository localRepo, List<Comment> comments,
                        List<Review> allReviews, List<Review> activeReviews, Set<String> labels, CensusInstance censusInstance,
                        boolean ignoreStaleReviews) throws IOException {
        var run = new CheckRun(workItem, pr, localRepo, comments, allReviews, activeReviews, labels, censusInstance, ignoreStaleReviews);
        run.checkStatus();
    }

    private boolean isTargetBranchAllowed() {
        var matcher = workItem.bot.allowedTargetBranches().matcher(pr.targetRef());
        return matcher.matches();
    }

    private Set<String> allowedIssueTypes() {
        return workItem.bot.allowedIssueTypes();
    }

    private List<Issue> issues() {
        var issue = Issue.fromStringRelaxed(pr.title());
        if (issue.isPresent()) {
            var issues = new ArrayList<Issue>();
            issues.add(issue.get());
            issues.addAll(SolvesTracker.currentSolved(pr.repository().forge().currentUser(), comments));
            return issues;
        }
        return List.of();
    }

    private IssueProject issueProject() {
        return workItem.bot.issueProject();
    }

    private List<org.openjdk.skara.issuetracker.Issue> issuesOfDisallowedType() {
        var issueProject = issueProject();
        var allowed = allowedIssueTypes();
        if (issueProject != null && allowed != null) {
            return issues().stream()
                           .filter(i -> i.project().equals(Optional.of(issueProject.name())))
                           .map(i -> issueProject.issue(i.shortId()))
                           .filter(Optional::isPresent)
                           .map(Optional::get)
                           .filter(i -> i.properties().containsKey("issuetype"))
                           .filter(i -> !allowed.contains(i.properties().get("issuetype").asString()))
                           .collect(Collectors.toList());
        }
        return List.of();
    }

    private List<String> allowedTargetBranches() {
        return pr.repository()
                 .branches()
                 .stream()
                 .map(HostedBranch::name)
                 .map(name -> workItem.bot.allowedTargetBranches().matcher(name))
                 .filter(Matcher::matches)
                 .map(Matcher::group)
                 .collect(Collectors.toList());
    }

    // For unknown contributors, check that all commits have the same name and email
    private boolean checkCommitAuthor(List<CommitMetadata> commits) throws IOException {
        var author = censusInstance.namespace().get(pr.author().id());
        if (author != null) {
            return true;
        }

        var names = new HashSet<String>();
        var emails = new HashSet<String>();

        for (var commit : commits) {
            names.add(commit.author().name());
            emails.add(commit.author().email());
        }

        return ((names.size() == 1) && emails.size() == 1);
    }

    // Additional bot-specific checks that are not handled by JCheck
    private List<String> botSpecificChecks(Hash finalHash) throws IOException {
        var ret = new ArrayList<String>();

        if (bodyWithoutStatus().isBlank()) {
            var error = "The pull request body must not be empty.";
            ret.add(error);
        }

        if (!isTargetBranchAllowed()) {
            var error = "The branch `" + pr.targetRef() + "` is not allowed as target branch. The allowed target branches are:\n" +
                    allowedTargetBranches().stream()
                    .map(name -> "   - " + name)
                    .collect(Collectors.joining("\n"));
            ret.add(error);
        }

        var disallowedIssues = issuesOfDisallowedType();
        if (!disallowedIssues.isEmpty()) {
            var s = disallowedIssues.size() > 1 ? "s " : " ";
            var are = disallowedIssues.size() > 1 ? "are" : "is";
            var links = disallowedIssues.stream()
                                        .map(i -> "[" + i.id() + "](" + i.webUrl() + ")")
                                        .collect(Collectors.toList());
            var error = "The issue" + s + String.join(",", links) + " " + are + " not of the expected type. The allowed issue types are:\n" +
                allowedIssueTypes().stream()
                .map(name -> "   - " + name)
                .collect(Collectors.joining("\n"));
            ret.add(error);
        }

        var headHash = pr.headHash();
        var originalCommits = localRepo.commitMetadata(baseHash, headHash);

        if (!checkCommitAuthor(originalCommits)) {
            var error = "For contributors who are not existing OpenJDK Authors, commit attribution will be taken from " +
                    "the commits in the PR. However, the commits in this PR have inconsistent user names and/or " +
                    "email addresses. Please amend the commits.";
            ret.add(error);
        }

        for (var blocker : workItem.bot.blockingCheckLabels().entrySet()) {
            if (labels.contains(blocker.getKey())) {
                ret.add(blocker.getValue());
            }
        }

        return ret;
    }

    private void updateCheckBuilder(CheckBuilder checkBuilder, PullRequestCheckIssueVisitor visitor, List<String> additionalErrors) {
        if (visitor.isReadyForReview() && additionalErrors.isEmpty()) {
            checkBuilder.complete(true);
        } else {
            checkBuilder.title("Required");
            var summary = Stream.concat(visitor.messages().stream(), additionalErrors.stream())
                                .sorted()
                                .map(m -> "- " + m)
                                .collect(Collectors.joining("\n"));
            checkBuilder.summary(summary);
            for (var annotation : visitor.getAnnotations()) {
                checkBuilder.annotation(annotation);
            }
            checkBuilder.complete(false);
        }
    }

    private void updateReadyForReview(PullRequestCheckIssueVisitor visitor, List<String> additionalErrors) {
        // Additional errors are not allowed
        if (!additionalErrors.isEmpty()) {
            newLabels.remove("rfr");
            return;
        }

        // Draft requests are not for review
        if (pr.isDraft()) {
            newLabels.remove("rfr");
            return;
        }

        // Check if the visitor found any issues that should be resolved before reviewing
        if (visitor.isReadyForReview()) {
            newLabels.add("rfr");
        } else {
            newLabels.remove("rfr");
        }
    }

    private String getRole(String username) {
        var project = censusInstance.project();
        var version = censusInstance.census().version().format();
        if (project.isReviewer(username, version)) {
            return "**Reviewer**";
        } else if (project.isCommitter(username, version)) {
            return "Committer";
        } else if (project.isAuthor(username, version)) {
            return "Author";
        } else {
            return "no project role";
        }
    }

    private String formatReviewer(HostUser reviewer) {
        var namespace = censusInstance.namespace();
        var contributor = namespace.get(reviewer.id());
        if (contributor == null) {
            return reviewer.userName() + " (no known " + namespace.name() + " user name / role)";
        } else {
            var userNameLink = "[" + contributor.username() + "](@" + reviewer.userName() + ")";
            return contributor.fullName().orElse(contributor.username()) + " (" + userNameLink + " - " +
                    getRole(contributor.username()) + ")";
        }
    }

    private String getChecksList(PullRequestCheckIssueVisitor visitor) {
        return visitor.getChecks().entrySet().stream()
                      .map(entry -> "- [" + (entry.getValue() ? "x" : " ") + "] " + entry.getKey())
                      .collect(Collectors.joining("\n"));
    }

    private String getAdditionalErrorsList(List<String> additionalErrors) {
        return additionalErrors.stream()
                               .sorted()
                               .map(err -> "&nbsp;⚠️ " + err)
                               .collect(Collectors.joining("\n"));
    }

    private Optional<String> getReviewersList(List<Review> reviews) {
        var reviewers = reviews.stream()
                               .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                               .map(review -> {
                                   var entry = " * " + formatReviewer(review.reviewer());
                                   if (!review.hash().equals(pr.headHash())) {
                                       if (ignoreStaleReviews) {
                                           entry += " 🔄 Re-review required (review applies to " + review.hash() + ")";
                                       } else {
                                           entry += " ⚠️ Review applies to " + review.hash();
                                       }
                                   }
                                   return entry;
                               })
                               .collect(Collectors.joining("\n"));

        // Check for manually added reviewers
        if (!ignoreStaleReviews) {
            var namespace = censusInstance.namespace();
            var allReviewers = PullRequestUtils.reviewerNames(reviews, namespace);
            var additionalEntries = new ArrayList<String>();
            for (var additional : Reviewers.reviewers(pr.repository().forge().currentUser(), comments)) {
                if (!allReviewers.contains(additional)) {
                    additionalEntries.add(" * " + additional + " - " + getRole(additional) + " ⚠️ Added manually");
                }
            }
            if (!reviewers.isBlank()) {
                reviewers += "\n";
            }
            reviewers += String.join("\n", additionalEntries);
        }

        if (reviewers.length() > 0) {
            return Optional.of(reviewers);
        } else {
            return Optional.empty();
        }
    }

    private String formatContributor(EmailAddress contributor) {
        var name = contributor.fullName().orElseThrow();
        return name + " `<" + contributor.address() + ">`";
    }

    private Optional<String> getContributorsList(List<Comment> comments) {
        var contributors = Contributors.contributors(pr.repository().forge().currentUser(), comments)
                                       .stream()
                                       .map(c -> " * " + formatContributor(c))
                                       .collect(Collectors.joining("\n"));
        if (contributors.length() > 0) {
            return Optional.of(contributors);
        } else {
            return Optional.empty();
        }
    }

    private boolean relaxedEquals(String s1, String s2) {
        return s1.trim()
                 .replaceAll("\\s+", " ")
                 .equalsIgnoreCase(s2.trim()
                                     .replaceAll("\\s+", " "));
    }


    private String getStatusMessage(List<Comment> comments, List<Review> reviews, PullRequestCheckIssueVisitor visitor, List<String> additionalErrors) {
        var progressBody = new StringBuilder();
        progressBody.append("---------\n");
        progressBody.append("### Progress\n");
        progressBody.append(getChecksList(visitor));

        var allAdditionalErrors = Stream.concat(visitor.hiddenMessages().stream(), additionalErrors.stream())
                                        .sorted()
                                        .collect(Collectors.toList());
        if (!allAdditionalErrors.isEmpty()) {
            progressBody.append("\n\n### Error");
            if (allAdditionalErrors.size() > 1) {
                progressBody.append("s");
            }
            progressBody.append("\n");
            progressBody.append(getAdditionalErrorsList(allAdditionalErrors));
        }

        var issues = issues();
        var issueProject = issueProject();
        if (issueProject != null && !issues.isEmpty()) {
            progressBody.append("\n\n### Issue");
            if (issues.size() > 1) {
                progressBody.append("s");
            }
            progressBody.append("\n");
            for (var currentIssue : issues) {
                progressBody.append(" * ");
                if (currentIssue.project().isPresent() && !currentIssue.project().get().equals(issueProject.name())) {
                    progressBody.append("⚠️ Issue `");
                    progressBody.append(currentIssue.id());
                    progressBody.append("` does not belong to the `");
                    progressBody.append(issueProject.name());
                    progressBody.append("` project.");
                } else {
                    try {
                        var iss = issueProject.issue(currentIssue.shortId());
                        if (iss.isPresent()) {
                            progressBody.append("[");
                            progressBody.append(iss.get().id());
                            progressBody.append("](");
                            progressBody.append(iss.get().webUrl());
                            progressBody.append("): ");
                            progressBody.append(iss.get().title());
                            if (!relaxedEquals(iss.get().title(), currentIssue.description())) {
                                progressBody.append(" ⚠️ Title mismatch between PR and JBS.");
                            }
                            progressBody.append("\n");
                        } else {
                            progressBody.append("⚠️ Failed to retrieve information on issue `");
                            progressBody.append(currentIssue.id());
                            progressBody.append("`.\n");
                        }
                    } catch (RuntimeException e) {
                        progressBody.append("⚠️ Temporary failure when trying to retrieve information on issue `");
                        progressBody.append(currentIssue.id());
                        progressBody.append("`.\n");
                    }
                }
            }
        }

        getReviewersList(reviews).ifPresent(reviewers -> {
            progressBody.append("\n\n### Reviewers\n");
            progressBody.append(reviewers);
        });

        getContributorsList(comments).ifPresent(contributors -> {
            progressBody.append("\n\n### Contributors\n");
            progressBody.append(contributors);
        });

        progressBody.append("\n\n### Download\n");
        progressBody.append(checkoutCommands());

        return progressBody.toString();
    }

    private String checkoutCommands() {
        var repoUrl = pr.repository().webUrl();
        return
           "`$ git fetch " + repoUrl + " " + pr.fetchRef() + ":pull/" + pr.id() + "`\n" +
           "`$ git checkout pull/" + pr.id() + "`\n";
    }

    private String bodyWithoutStatus() {
        var description = pr.body();
        var markerIndex = description.lastIndexOf(progressMarker);
        return (markerIndex < 0 ?
                description :
                description.substring(0, markerIndex)).trim();
    }

    private String updateStatusMessage(String message) {
        var description = pr.body();
        var markerIndex = description.lastIndexOf(progressMarker);

        if (markerIndex >= 0 && description.substring(markerIndex).equals(message)) {
            log.info("Progress already up to date");
            return description;
        }
        var originalBody = bodyWithoutStatus();
        if (originalBody.isBlank()) {
            originalBody = emptyPrBodyMarker;
        }
        var newBody = originalBody + "\n" + progressMarker + "\n" + message;

        // TODO? Retrieve the body again here to lower the chance of concurrent updates
        pr.setBody(newBody);
        return newBody;
    }

    private String updateTitle() {
        var title = pr.title();
        var m = ISSUE_ID_PATTERN.matcher(title);
        var project = issueProject();

        var newTitle = title;
        if (m.matches() && project != null) {
            var id = m.group(1);
            var issue = project.issue(id);
            if (issue.isPresent()) {
                newTitle = id + ": " + issue.get().title();
            }
        }

        if (!title.equals(newTitle)) {
            pr.setTitle(newTitle);
        }

        return newTitle;
    }

    private String verdictToString(Review.Verdict verdict) {
        switch (verdict) {
            case APPROVED:
                return "changes are approved";
            case DISAPPROVED:
                return "more changes needed";
            case NONE:
                return "comment added";
            default:
                throw new RuntimeException("Unknown verdict: " + verdict);
        }
    }

    private void updateReviewedMessages(List<Comment> comments, List<Review> reviews) {
        var reviewTracker = new ReviewTracker(comments, reviews);

        for (var added : reviewTracker.newReviews().entrySet()) {
            var body = added.getValue() + "\n" +
                    "This PR has been reviewed by " +
                    formatReviewer(added.getKey().reviewer()) + " - " +
                    verdictToString(added.getKey().verdict()) + ".";
            pr.addComment(body);
        }
    }

    private Optional<Comment> findComment(List<Comment> comments, String marker) {
        var self = pr.repository().forge().currentUser();
        return comments.stream()
                       .filter(comment -> comment.author().equals(self))
                       .filter(comment -> comment.body().contains(marker))
                       .findAny();
    }

    private String getMergeReadyComment(String commitMessage, List<Review> reviews) {
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().userName());
        message.append(" This change now passes all automated pre-integration checks");

        try {
            var hasContributingFile =
                !localRepo.files(pr.targetHash(), Path.of("CONTRIBUTING.md")).isEmpty();
            if (hasContributingFile) {
                message.append(". When the change also fulfills all ");
                message.append("[project specific requirements](https://github.com/");
                message.append(pr.repository().name());
                message.append("/blob/");
                message.append(pr.targetRef());
                message.append("/CONTRIBUTING.md)");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        message.append(", type `/integrate` in a new comment to proceed. After integration, ");
        message.append("the commit message will be:\n");
        message.append("```\n");
        message.append(commitMessage);
        message.append("\n```\n");

        message.append("- If you would like to add a summary, use the `/summary` command.\n");
        message.append("- To credit additional contributors, use the `/contributor` command.\n");
        message.append("- To add additional solved issues, use the `/issue` command.\n");

        var divergingCommits = checkablePullRequest.divergingCommits();
        if (divergingCommits.size() > 0) {
            message.append("\n");
            message.append("Since the source branch of this PR was last updated there ");
            if (divergingCommits.size() == 1) {
                message.append("has been 1 commit ");
            } else {
                message.append("have been ");
                message.append(divergingCommits.size());
                message.append(" commits ");
            }
            message.append("pushed to the `");
            message.append(pr.targetRef());
            message.append("` branch:\n\n");
            divergingCommits.stream()
                            .limit(10)
                            .forEach(c -> message.append(" * ").append(c.hash().hex()).append(": ").append(c.message().get(0)).append("\n"));
            if (divergingCommits.size() > 10) {
                message.append(" * ... and ").append(divergingCommits.size() - 10).append(" more: ")
                       .append(pr.repository().webUrl(baseHash.hex(), pr.targetRef())).append("\n");
            }

            message.append("\n");
            message.append("As there are no conflicts, your changes will automatically be rebased on top of ");
            message.append("these commits when integrating. If you prefer to avoid automatic rebasing, please merge `");
            message.append(pr.targetRef());
            message.append("` into your branch, and then specify the current head hash when integrating, like this: ");
            message.append("`/integrate ");
            message.append(pr.targetHash());
            message.append("`.\n");
        } else {
            message.append("\n");
            message.append("There are currently no new commits on the `");
            message.append(pr.targetRef());
            message.append("` branch since the last update of the source branch of this PR. If another commit should be pushed before ");
            message.append("you perform the `/integrate` command, your PR will be automatically rebased. If you would like to avoid ");
            message.append("potential automatic rebasing, specify the current head hash when integrating, like this: ");
            message.append("`/integrate ");
            message.append(pr.targetHash());
            message.append("`.\n");
        }

        if (!ProjectPermissions.mayCommit(censusInstance, pr.author())) {
            message.append("\n");
            var contributor = censusInstance.namespace().get(pr.author().id());
            if (contributor == null) {
                message.append("As you are not a known OpenJDK [Author](https://openjdk.java.net/bylaws#author), ");
            } else {
                message.append("As you do not have Committer status in this project, ");
            }

            message.append("an existing [Committer](https://openjdk.java.net/bylaws#committer) must agree to ");
            message.append("[sponsor](https://openjdk.java.net/sponsor/) your change. ");
            var candidates = reviews.stream()
                                    .filter(review -> ProjectPermissions.mayCommit(censusInstance, review.reviewer()))
                                    .map(review -> "@" + review.reviewer().userName())
                                    .collect(Collectors.joining(", "));
            if (candidates.length() > 0) {
                message.append("Possible candidates are the reviewers of this PR (");
                message.append(candidates);
                message.append(") but any other Committer may sponsor as well. ");
            }
            message.append("\n\n");
            message.append("➡️ To flag this PR as ready for integration with the above commit message, type ");
            message.append("`/integrate` in a new comment. (Afterwards, your sponsor types ");
            message.append("`/sponsor` in a new comment to perform the integration).\n");
        } else {
            message.append("\n");
            message.append("➡️ To integrate this PR with the above commit message to the `" + pr.targetRef() + "` branch, type ");
            message.append("`/integrate` in a new comment.\n");
        }
        message.append(mergeReadyMarker);
        return message.toString();
    }

    private String getMergeNoLongerReadyComment() {
        var message = new StringBuilder();
        message.append("@");
        message.append(pr.author().userName());
        message.append(" This change is no longer ready for integration - check the PR body for details.\n");
        message.append(mergeReadyMarker);
        return message.toString();
    }

    private void updateMergeReadyComment(boolean isReady, String commitMessage, List<Comment> comments, List<Review> reviews, boolean rebasePossible) {
        var existing = findComment(comments, mergeReadyMarker);
        if (isReady && rebasePossible) {
            var message = getMergeReadyComment(commitMessage, reviews);
            if (existing.isEmpty()) {
                pr.addComment(message);
            } else {
                pr.updateComment(existing.get().id(), message);
            }
        } else {
            existing.ifPresent(comment -> pr.updateComment(comment.id(), getMergeNoLongerReadyComment()));
        }
    }

    private void addSourceBranchWarningComment(List<Comment> comments) throws IOException {
        var existing = findComment(comments, sourceBranchWarningMarker);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }
        var branch = pr.sourceRef();
        var message = ":warning: @" + pr.author().userName() + " " +
            "a branch with the same name as the source branch for this pull request (`" + branch + "`) " +
            "is present in the [target repository](" + pr.repository().nonTransformedWebUrl() + "). " +
            "If you eventually integrate this pull request then the branch `" + branch + "` " +
            "in your [personal fork](" + pr.sourceRepository().nonTransformedWebUrl() + ") will diverge once you sync " +
            "your personal fork with the upstream repository.\n" +
            "\n" +
            "To avoid this situation, create a new branch for your changes and reset the `" + branch + "` branch. " +
            "You can do this by running the following commands in a local repository for your personal fork. " +
            "_Note_: you do *not* have to name the new branch `NEW-BRANCH-NAME`." +
            "\n" +
            "```" +
            "$ git checkout " + branch + "\n" +
            "$ git checkout -b NEW-BRANCH-NAME\n" +
            "$ git branch -f " + branch + " " + baseHash.hex() + "\n" +
            "$ git push -f origin " + branch + "\n" +
            "```\n" +
            "\n" +
            "Then proceed to create a new pull request with `NEW-BRANCH-NAME` as the source branch and " +
            "close this one.\n" +
            sourceBranchWarningMarker;
        pr.addComment(message);
    }

    private void addOutdatedComment(List<Comment> comments) {
        var existing = findComment(comments, outdatedHelpMarker);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }
        var message = "@" + pr.author().userName() + " this pull request can not be integrated into " +
                "`" + pr.targetRef() + "` due to one or more merge conflicts. To resolve these merge conflicts " +
                "and update this pull request you can run the following commands in the local repository for your personal fork:\n" +
                "```bash\n" +
                "git checkout " + pr.sourceRef() + "\n" +
                "git fetch " + pr.repository().webUrl() + " " + pr.targetRef() + "\n" +
                "git merge FETCH_HEAD\n" +
                "# resolve conflicts and follow the instructions given by git merge\n" +
                "git commit -m \"Merge " + pr.targetRef() + "\"\n" +
                "git push\n" +
                "```\n" +
                outdatedHelpMarker;
        pr.addComment(message);
    }

    private void addMergeCommitWarningComment(List<Comment> comments) {
        var existing = findComment(comments, mergeCommitWarningMarker);
        if (existing.isPresent()) {
            // Only add the comment once per PR
            return;
        }

        var message = "@" + pr.author().userName() + " This pull request looks like it contains a merge commit that " +
                "brings in commits from outside of this repository. If you want these commits to be preserved " +
                "when you integrate, you will need to make a 'merge-style' pull request. To do this, change the " +
                "title of this pull request to `Merge <project>:<branch>`, where `<project>` is the name of another " +
                "project in the OpenJDK organization. For example: `Merge jdk:master`." +
                "\n" + mergeCommitWarningMarker;
        pr.addComment(message);
    }

    private void checkStatus() {
        var checkBuilder = CheckBuilder.create("jcheck", pr.headHash());
        var censusDomain = censusInstance.configuration().census().domain();
        Exception checkException = null;

        try {
            // Post check in-progress
            log.info("Starting to run jcheck on PR head");
            pr.createCheck(checkBuilder.build());

            var ignored = new PrintWriter(new StringWriter());
            var rebasePossible = true;
            var commitHash = pr.headHash();
            var mergedHash = checkablePullRequest.mergeTarget(ignored);
            if (mergedHash.isPresent()) {
                commitHash = mergedHash.get();
            } else {
                rebasePossible = false;
            }

            List<String> additionalErrors = List.of();
            Hash localHash;
            try {
                localHash = checkablePullRequest.commit(commitHash, censusInstance.namespace(), censusDomain, null);
            } catch (CommitFailure e) {
                additionalErrors = List.of(e.getMessage());
                localHash = baseHash;
            }
            PullRequestCheckIssueVisitor visitor = checkablePullRequest.createVisitor(localHash, censusInstance);
            if (!localHash.equals(baseHash)) {
                // Determine current status
                var additionalConfiguration = AdditionalConfiguration.get(localRepo, localHash, pr.repository().forge().currentUser(), comments);
                checkablePullRequest.executeChecks(localHash, censusInstance, visitor, additionalConfiguration);
                additionalErrors = botSpecificChecks(localHash);
            } else {
                if (additionalErrors.isEmpty()) {
                    additionalErrors = List.of("This PR contains no changes");
                }
            }
            updateCheckBuilder(checkBuilder, visitor, additionalErrors);
            updateReadyForReview(visitor, additionalErrors);

            // Calculate and update the status message if needed
            var statusMessage = getStatusMessage(comments, activeReviews, visitor, additionalErrors);
            var updatedBody = updateStatusMessage(statusMessage);
            var title = updateTitle();

            // Post / update approval messages (only needed if the review itself can't contain a body)
            if (!pr.repository().forge().supportsReviewBody()) {
                updateReviewedMessages(comments, allReviews);
            }

            var commit = localRepo.lookup(localHash).orElseThrow();
            var commitMessage = String.join("\n", commit.message());
            var readyForIntegration = visitor.messages().isEmpty() && additionalErrors.isEmpty();
            updateMergeReadyComment(readyForIntegration, commitMessage, comments, activeReviews, rebasePossible);
            if (readyForIntegration && rebasePossible) {
                newLabels.add("ready");
            } else {
                newLabels.remove("ready");
            }
            if (!rebasePossible) {
                if (!labels.contains("failed-auto-merge")) {
                    addOutdatedComment(comments);
                }
                newLabels.add("merge-conflict");
            } else {
                newLabels.remove("merge-conflict");
            }

            var branchNames = pr.repository().branches().stream().map(HostedBranch::name).collect(Collectors.toSet());
            if (!pr.repository().url().equals(pr.sourceRepository().url()) && branchNames.contains(pr.sourceRef())) {
                addSourceBranchWarningComment(comments);
            }

            if (!PullRequestUtils.isMerge(pr) && PullRequestUtils.containsForeignMerge(pr, localRepo)) {
                addMergeCommitWarningComment(comments);
            }

            // Ensure that the ready for sponsor label is up to date
            newLabels.remove("sponsor");
            var readyHash = ReadyForSponsorTracker.latestReadyForSponsor(pr.repository().forge().currentUser(), comments);
            if (readyHash.isPresent() && readyForIntegration) {
                var acceptedHash = readyHash.get();
                if (pr.headHash().equals(acceptedHash)) {
                    newLabels.add("sponsor");
                }
            }

            // Calculate current metadata to avoid unnecessary future checks
            var metadata = workItem.getMetadata(title, updatedBody, pr.comments(), activeReviews, newLabels,
                                                censusInstance, pr.targetHash(), pr.isDraft());
            checkBuilder.metadata(metadata);
        } catch (Exception e) {
            log.throwing("CommitChecker", "checkStatus", e);
            newLabels.remove("ready");
            checkBuilder.metadata("invalid");
            checkBuilder.title("Exception occurred during jcheck - the operation will be retried");
            checkBuilder.summary(e.getMessage());
            checkBuilder.complete(false);
            checkException = e;
        }
        var check = checkBuilder.build();
        pr.updateCheck(check);

        // Synchronize the wanted set of labels
        for (var newLabel : newLabels) {
            if (!labels.contains(newLabel)) {
                pr.addLabel(newLabel);
            }
        }
        for (var oldLabel : labels) {
            if (!newLabels.contains(oldLabel)) {
                pr.removeLabel(oldLabel);
            }
        }

        // After updating the PR, rethrow any exception to automatically retry on transient errors
        if (checkException != null) {
            throw new RuntimeException("Exception during jcheck", checkException);
        }
    }
}
