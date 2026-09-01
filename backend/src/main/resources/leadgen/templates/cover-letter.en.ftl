<#-- The English cover letter, used when the ad is written in English. -->
Dear Sir or Madam,

I came across your posting "${offer.title}"<#if offer.portal??> on ${offer.portal}</#if><#if offer.agency??>, advertised by ${offer.agency}</#if>.

<#if matchedSkills?has_content>
The focus you describe — ${matchedSkills?join(", ")} — has been the core of my work for years.
</#if>
<#list projects as project>

${project.title}<#if project.period??> (${project.period})</#if>
${project.pitchEn!project.pitchDe!""}
</#list>

<#if offer.startsOn??>
I am available from ${offer.startsOn}.
</#if>
My CV is attached. I would be glad to talk.

Kind regards
${profile.identity.name}<#if profile.identity.brand??>
${profile.identity.brand}</#if>
