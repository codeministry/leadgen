<#-- The German cover letter. Content, not repository language: this text goes
     verbatim to a client, so it is written in the language of the ad.
     Available: offer, profile, projects (the reference projects the offer's own
     skills selected), score. -->
Sehr geehrte Damen und Herren,

<#if offer.agency??>über ${offer.agency} bin ich auf<#else>ich bin auf</#if> Ihre Ausschreibung „${offer.title}“ gestoßen<#if offer.portal??> (${offer.portal})</#if>.

<#if matchedSkills?has_content>
Die geforderten Schwerpunkte ${matchedSkills?join(", ")} bilden seit Jahren den Kern meiner Arbeit.
</#if>
<#list projects as project>

${project.title}<#if project.period??> (${project.period})</#if>
${project.pitchDe!""}
</#list>

<#if offer.startsOn??>
Ein Einstieg zum ${offer.startsOn} ist möglich.
</#if>
Meinen Lebenslauf finden Sie im Anhang. Über ein Gespräch freue ich mich.

Mit freundlichen Grüßen
${profile.identity.name}<#if profile.identity.brand??>
${profile.identity.brand}</#if>
