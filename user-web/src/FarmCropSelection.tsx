import { useState } from "react";
import { cropGroups, getCropOptions } from "./farmCropCatalog";

export default function FarmCropSelection() {
  const [group, setGroup] = useState("");
  const [crop, setCrop] = useState("");
  return (
    <div className="form-grid">
      <label>
        작물 분류
        <select name="cropGroup" value={group} required onChange={(event) => {
          setGroup(event.currentTarget.value);
          setCrop("");
        }}>
          <option value="">전체</option>
          {cropGroups.map((value) => <option key={value}>{value}</option>)}
        </select>
      </label>
      <label>
        품목
        <select name="crop" value={crop} required onChange={(event) => setCrop(event.currentTarget.value)}>
          <option value="">전체</option>
          {getCropOptions(group).map((value) => <option key={value}>{value}</option>)}
        </select>
      </label>
    </div>
  );
}
