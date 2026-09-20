<#-- The German cover letter. Content, not repository language: this text goes
     verbatim to a client, so it is written in the language of the ad.
     Available: offer, profile, projects (ProjectView, already in this letter's
     language), matchedSkills, startsOnText, score.

     Every text line below starts in column 0, and it has to. Freemarker strips a line
     holding nothing but a directive; it does not strip the indentation of a text line
     inside an <#if> or a <#list>, so an indented body reaches the client indented. -->
Sehr geehrte Damen und Herren,

<#if offer.portal??>Über ${offer.portal} bin ich auf<#else>Ich bin auf</#if> Ihre Ausschreibung „${offer.title}“ gestoßen.

<#if matchedSkills?has_content>
Die geforderten Schwerpunkte ${matchedSkills?join(", ")} bilden seit Jahren den Kern meiner Arbeit.
</#if>
<#list projects as project>

${project.title}<#if project.period??> (${project.period})</#if>
${project.pitch!""}
</#list>

<#if startsOnText??>
Ein Einstieg zum ${startsOnText} ist möglich.
</#if>
Meinen Lebenslauf finden Sie im Anhang. Für ein kurzes Gespräch, in dem wir Details und Verfügbarkeit klären, stehe ich Ihnen gerne zur Verfügung.

Mit freundlichen Grüßen
${profile.identity.name}<#if profile.identity.brand??>
${profile.identity.brand}</#if>
