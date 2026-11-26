import { useState } from "react";
import FarmIcon from "./FarmIcon";
import FarmWorkplaceMap from "./FarmWorkplaceMap";
import type { FarmJob } from "./FarmDiscovery";

export default function FarmLocationDisclosure({ job }: { job: FarmJob }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <details className="farm-location-disclosure" onToggle={(event) => setExpanded(event.currentTarget.open)}>
      <summary><FarmIcon name="pin" />약도 보기</summary>
      {expanded && <FarmWorkplaceMap address={job.place} registeredPin={job.workplacePin} />}
    </details>
  );
}
