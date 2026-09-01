<#-- The ad as it was when the decision was made. Portals take listings down, and
     a package without the original is a package nobody can check later. -->
${offer.title}
${"="?left_pad(offer.title?length, "=")}

Portal:     ${offer.portal!"unknown"}
Agency:     ${offer.agency!"none stated"}
Location:   ${offer.location!"unknown"}
Rate:       <#if offer.rateEur??>${offer.rateEur} EUR/h<#else>not stated</#if>
Duration:   ${offer.duration!"not stated"}
Workload:   ${offer.workload!"not stated"}
Start:      ${offer.startsOn!"not stated"}
Published:  ${offer.publishedOn!"unknown"}
URL:        ${offer.url!""}
Archived:   ${archivedAt}

<#if offer.description??>
${offer.description}
</#if>
<#if offer.fullText??>

--- the original ad ---

${offer.fullText}
</#if>
