# SMART Diff usability study: guide for participants

SMART Diff is looking for volunteers for a 15–25 minute usability session. The study checks whether the plugin's structural outline helps a reviewer find a changed declaration faster than the IDE's normal diff, without more wrong answers.

This page is for participants. It describes the session and does not show the tasks or their answers. The facilitator's material is in this folder's [README](README.md).

## What you are helping with

SMART Diff is a plugin for IntelliJ IDEA and other JetBrains IDEs. Next to the normal side-by-side diff, it shows a structural outline that lists which declarations, such as classes, methods and functions, were added, removed, renamed or changed.

You will do some tasks with the outline and some with the normal diff alone. Your times are compared with other participants' times as a group, not judged on their own. A slow or wrong answer is a useful result, not a mistake on your part.

## Who can take part

You can take part if all three of these are true:

- You can read Java, Kotlin and Scala code. Reading is enough; you don't need to write any of them.
- You did not write or review any of the changes used in the study. They come from Spring PetClinic, OkHttp, ZIO, Scala 3 and Exposed.
- You have not already done a session of this study. Each person takes part once.

You don't need to know SMART Diff or IntelliJ IDEA well.

## How to volunteer

Open an issue in [agadek/smart-diff](https://github.com/agadek/smart-diff/issues/new?title=Usability%20study%20volunteer) titled **Usability study volunteer**. Say only that you'd like to take part and which days suit you. Don't put contact details in the issue. The maintainer will reply there to arrange a time, then close the issue. The issue is never linked to your results.

## The session

The session takes 15–25 minutes. It runs on the maintainer's prepared IntelliJ IDEA setup, either in person or over a screen-sharing call where you control the IDE, so you don't install anything.

1. **Welcome (2 minutes).** You get a short introduction and can ask questions about how the session works.
2. **Five tasks (up to 3 minutes each).** For each task, one small real code change is shown as a diff. You are told whether the outline is shown for that task. Then one question is read aloud, for example which method was added.
3. **Answer out loud.** Name the declaration and say how it changed, for example "method X was added". The timer stops when you have said both. Exact wording doesn't matter.
4. **Short break between tasks.** The answers aren't discussed until the end.
5. **Wrap-up (2 minutes).** Once all five tasks are done, you can ask for the answers and share any comments about the plugin.

During the tasks:

- You may scroll the diff, and expand or click rows in the outline.
- Please don't use Search Everywhere, the Project view, or open the file in the editor. The study measures reading the diff.
- If you have no answer after 3 minutes, the task ends and you move on to the next one. That's fine and still useful.
- You can stop at any time without giving a reason.

## Privacy

Your name is never recorded, and no audio, video or screen recording is made. For each task, only these items are written down:

- an anonymous code, such as P03
- the task number
- whether the outline was shown
- the time in seconds
- whether the answer was right

The screening answer is recorded as yes or no next to your code. Your role, employer and contact details aren't recorded with your results. The anonymous results are published in this repository's [VALIDATION.md](../VALIDATION.md) once the study is complete.

## Questions people ask

**Can I see the changes beforehand?** No. Knowing a change would make your time meaningless for that task. Please don't read this folder's `tasks.md` before your session.

**Is this a test of my skills?** No. The study compares two ways of showing a diff. Your times only count as part of the group.

**Can I try the plugin afterwards?** Yes. SMART Diff is on the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36327-smart-diff), and issues and ideas are welcome after your session.

**Can a colleague take part too?** Yes, if they meet the conditions above. Please don't tell them about the tasks or the answers.

**What if I make a mistake?** Answer as you would in a real review. A wrong answer is recorded like any other and is useful data.

## Inviting someone

To invite a person directly, send them a link to this page with a short note, for example:

> Could you give me 15–25 minutes for a usability session of SMART Diff, a JetBrains plugin for reviewing diffs? You'll look at five real code changes and say which declaration changed and how; I time each answer. You need to read Java, Kotlin and Scala, and nothing about you is recorded except an anonymous code and the times. Details: https://github.com/agadek/smart-diff/blob/main/docs/usability-study/participant-guide.md
