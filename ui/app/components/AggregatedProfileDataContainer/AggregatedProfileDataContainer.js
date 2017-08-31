import React from 'react';
import { withRouter } from 'react-router';
import { connect } from 'react-redux';

import CPUSampling from 'components/CPUSamplingComponent';
import safeTraverse from 'utils/safeTraverse';
import styles from './AggregatedProfileDataContainer.css';

const workTypeMap = {
  cpu_sample_work: {
    text: 'CPU SAMPLE',
    component: CPUSampling,
  },
};

function defaultSelectWorkType (props) {
  if (!props.workTypes) return;
  const location = props.location;
  props.router.push({
    ...location,
    query: {
      ...location.query,
      selectedWorkType: props.workTypes[0].workType,
    },
  });
}

class AggregatedProfileDataContainer extends React.Component {
  componentDidMount () {
    defaultSelectWorkType(this.props);
  }

  componentWillReceiveProps (nextProps) {
    // if there's already a selected workType,
    // and user clicked a different profile, handle!
    // also handle if a different trace is clicked
    if (nextProps.location.query.selectedWorkType) {
      // see if availableWorkTypes have it
      const isSelectedWorkTypeValid = nextProps.workTypes
        .filter(w => w.workType === nextProps.location.query.selectedWorkType);
      if (!isSelectedWorkTypeValid.length) defaultSelectWorkType(nextProps);
      return;
    }
    defaultSelectWorkType(nextProps);
  }

  render () {
    const selectedWorkType = this.props.location.query.selectedWorkType;
    const Komponent = this.props.workTypes && selectedWorkType && workTypeMap[selectedWorkType] &&
      workTypeMap[selectedWorkType].component;
    return (
      <div className={styles['aggregated-container']}>
        <div className={styles['header']}>
          {this.props.workTypes && this.props.workTypes.map(w => (
            <button
              key={w.workType}
              className="mdl-button mdl-js-button mdl-js-ripple-effect"
              style={
                selectedWorkType === w.workType
                  ? { background: 'rgb(63,81,181)', color: 'white', cursor: 'default' } : {}
              }
            >
              {`${workTypeMap[w.workType].text} (${w.samples})`}
            </button>
          ))}
        </div>
        <div>
          {Komponent && (
            <Komponent
              location={this.props.location}
              params={this.props.params}
            />
          )}
        </div>
      </div>
    );
  }
}
const mapStateToProps = (state, ownProps) => {
  const { traceName } = ownProps.params;
  const { profileStart } = ownProps.location.query;
  // taking only successful ones for now
  const allProfiles = safeTraverse(state, ['profiles', 'data', 'succeeded']) || [];
  const filteredProfile = allProfiles.filter(p => p.start === profileStart)[0];
  const traceIndex = filteredProfile && filteredProfile.traces.indexOf(traceName);
  // find what all work types are associated with it :/
  // traceIndex to workType relationship is stored in key `ws_summary`
  const workTypes = filteredProfile && Object.keys((filteredProfile).ws_summary).reduce((prev, curr) => {
    const traceArray = filteredProfile.ws_summary[curr].traces
      .filter(t => t.trace_idx === traceIndex);
    if (traceArray.length) {
      prev = [
        ...prev,
        { workType: curr, samples: traceArray[0].props.samples },
      ];
    }
    return prev;
  }, []);
  return {
    workTypes,
  };
};

export default connect(mapStateToProps, null)(withRouter(AggregatedProfileDataContainer));
