// The floor behind `retrieval.topic_floor`, measured from a person's labels rather than chosen.
//
// A topic's name is a few words and an advert is thousands of characters, so the
// advert-against-advert bands `measure_embeddings.ts` produces say nothing about this
// comparison. What does is a labelled sample: the offers nearest to the topic's name that
// name none of its aliases, each marked by the operator as really about the topic or not.
// This script reads those marks and prints, for every candidate floor, how many offers it
// would add and how many of them are right.
//
// ## Producing the sample
//
// Embed the topic's name with the configured embedding model, truncated to the column's
// 2000 dimensions exactly as `QueryEmbedder` does, and rank the stored retrieval vectors by
// cosine against it — offers carrying an alias match excluded, since the stored match already
// finds those. Write the top of that ranking into a Markdown file, one line per offer:
//
//     - [ ] #<offer id> · <similarity, three decimals> · <anything else you want to read>
//
// under a `## <topic name>` heading per topic, then tick `[x]` on every offer that is really
// about the topic. Keep the file out of the repository: it names the operator's interests.
//
// ## Reading the result
//
// Set the floor where precision is still acceptable, and between two neighbouring
// similarities rather than on one: a floor equal to a labelled value keeps that offer and
// sits one rounding error away from dropping it. If no floor reaches the precision you
// want, leave `topic_floor` unset — the topic filter then reads the stored alias matches
// alone, which is complete, and the judge still finds paraphrases for the score.
//
// Usage: bun docs/samples/measure_topic_floor.ts <labels.md>

import {readFileSync} from "fs";

const file = process.argv[2];
if (!file) {
    console.error("usage: bun docs/samples/measure_topic_floor.ts <labels.md>");
    process.exit(1);
}

const text = readFileSync(file, "utf8");
const sections = text.split(/^## /m).slice(1);
if (sections.length === 0) {
    console.error("no `## <topic>` section found");
    process.exit(1);
}

for (const section of sections) {
    const topic = section.split("\n", 1)[0].trim();
    const rows = [...section.matchAll(/^- \[( |x)\] #(\d+) · (\d\.\d+)/gm)]
        .map((m) => ({yes: m[1] === "x", id: m[2], similarity: Number(m[3])}))
        .sort((a, b) => b.similarity - a.similarity);
    const positives = rows.filter((row) => row.yes).length;
    console.log(`\n## ${topic}: ${rows.length} labelled, ${positives} about the topic`);
    console.log("floor  | added | right | precision | share of the labelled positives");
    const floors = [...new Set(rows.map((row) => row.similarity))];
    for (const floor of floors) {
        const added = rows.filter((row) => row.similarity >= floor);
        const right = added.filter((row) => row.yes).length;
        const precision = (100 * right) / added.length;
        const recall = positives === 0 ? 0 : (100 * right) / positives;
        console.log(
            `${floor.toFixed(3)}  | ${String(added.length).padStart(5)} | ${String(right).padStart(5)} |` +
                ` ${precision.toFixed(0).padStart(7)} % | ${recall.toFixed(0).padStart(5)} %`,
        );
    }
}
