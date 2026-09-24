<#-- The English cover letter, used when the ad is written in English. The same column-0
     rule as the German one: an indented text line inside a directive body stays indented
     in the rendered letter. `closing` is the closing line from cover-letter.yaml, absent
     when that file names no rules for English, and the literal below is printed instead. -->
Dear Sir or Madam,

I came across your posting "${offer.title}"<#if offer.portal??> on ${offer.portal}</#if>.

<#if matchedSkills?has_content>
The focus you describe — ${matchedSkills?join(", ")} — has been the core of my work for years.
</#if>
<#list projects as project>

${project.title}<#if project.period??> (${project.period})</#if>
${project.pitch!""}
</#list>

<#if startsOnText??>
I am available from ${startsOnText}.
</#if>
My CV is attached. I would be glad to discuss the details and my availability with you in a short call.

${closing!"Kind regards"}
${profile.identity.name}<#if profile.identity.brand??>
${profile.identity.brand}</#if>
