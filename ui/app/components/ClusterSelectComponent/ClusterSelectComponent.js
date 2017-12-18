import React, { Component, PropTypes } from 'react';
import { connect } from 'react-redux';
import Select, {Creatable} from 'react-select';

import fetchProfilesClusterAction from 'actions/ProfilesClusterActions';
import safeTraverse from 'utils/safeTraverse';

import styles from './ClusterSelectComponent.css';

import fetchPoliciesClusterAction from 'actions/PoliciesClusterActions';
import debounce from 'utils/debounce';

const noop = () => {};

class ClusterSelectComponent extends Component {
  componentDidMount () {
    const { app } = this.props;
    if (app) {
      this.props.isPoliciesPage ? this.props.getPolicyClusters(app)() : this.props.getProfilesClusters(app)();
    }
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.app !== this.props.app || nextProps.isPoliciesPage !== this.props.isPoliciesPage) {
      nextProps.isPoliciesPage ? this.props.getPolicyClusters(nextProps.app)() : this.props.getProfilesClusters(nextProps.app)();
    }
  }

  render() {
    const {onChange, profilesClusters, policiesClusters} = this.props;
    const clustersResponse = this.props.isPoliciesPage ? policiesClusters : profilesClusters;
    const clusters = clustersResponse.asyncStatus === 'SUCCESS'
      ? clustersResponse.data.map(c => ({name: c})) : [];
    const valueOption = this.props.value && {name: this.props.value};
    return (
      <div>
        <label className={styles.label} htmlFor="cluster">Cluster</label>
        {this.props.isPoliciesPage &&
        <Creatable
          id="cluster"
          clearable={false}
          options={clusters}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getPolicyClusters(this.props.app), 500)}
          isLoading={clustersResponse.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={clustersResponse.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
          promptTextCreator={(label) => "Add cluster: " + label}
          onOpen={()=>(this.props.value && this.creatable.select && this.creatable.select.setState({inputValue: this.props.value}))}
          ref={(ref)=> this.creatable = ref}
        />}
        {!this.props.isPoliciesPage &&
        < Select
          id="cluster"
          clearable={false}
          options={clusters}
          onChange={onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getProfilesClusters(this.props.app), 500)}
          isLoading={clustersResponse.asyncStatus === 'PENDING'}
          value={valueOption}
          noResultsText={clustersResponse.asyncStatus !== 'PENDING' ? 'No results found!' : 'Searching...'}
          placeholder="Type to search..."
          onOpen={()=>(this.props.value && this.select && this.select.setState({inputValue: this.props.value}))}
          ref={(ref)=> this.select = ref}
        />
        }
      </div>
    );
  }
}

const mapStateToProps = (state, ownProps) => ({
  profilesClusters: safeTraverse(state, ['profilesClusters', ownProps.app]) || {},
  policiesClusters: safeTraverse(state, ['policiesClusters', ownProps.app]) || {}
});

const mapDispatchToProps = dispatch => ({
  getProfilesClusters: app => event => {dispatch(fetchProfilesClusterAction(app, event));},
  getPolicyClusters: app => event => dispatch(fetchPoliciesClusterAction(app, event)),
});

ClusterSelectComponent.propTypes = {
  app: PropTypes.string,
  profilesClusters: PropTypes.object.isRequired,
  policiesClusters: PropTypes.object.isRequired,
  getProfilesClusters: PropTypes.func.isRequired,
  getPolicyClusters: PropTypes.func.isRequired,
  onChange: PropTypes.func,
  isPoliciesPage: PropTypes.bool.isRequired,
};

export default connect(mapStateToProps, mapDispatchToProps)(ClusterSelectComponent);
